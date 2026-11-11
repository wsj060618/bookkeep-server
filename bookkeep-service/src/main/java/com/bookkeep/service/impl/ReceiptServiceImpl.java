package com.bookkeep.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.bookkeep.common.exception.BusinessException;
import com.bookkeep.common.result.ResultCode;
import com.bookkeep.common.util.SecurityUtil;
import com.bookkeep.model.dto.resp.ReceiptImportResp;
import com.bookkeep.model.dto.resp.ReceiptResp;
import com.bookkeep.model.entity.Book;
import com.bookkeep.model.entity.Transaction;
import com.bookkeep.model.entity.TransactionReceipt;
import com.bookkeep.service.CurrentBookService;
import com.bookkeep.service.ReceiptService;
import com.bookkeep.service.mapper.BookMapper;
import com.bookkeep.service.mapper.TransactionMapper;
import com.bookkeep.service.mapper.TransactionReceiptMapper;
import com.bookkeep.service.support.ReceiptStorage;
import com.bookkeep.service.support.ShareLinkResolver;
import com.bookkeep.service.support.ZipExtractor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * 票据业务实现。
 * <p>顺序永远是「先校验归属，再碰磁盘」：越权请求不该在服务器上留下任何文件。
 * <p>库里存的是相对路径，磁盘上按「账本名_bookId/yyyy-MM/uuid.ext」分层，账本改名不搬目录。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReceiptServiceImpl implements ReceiptService {

    private final TransactionMapper transactionMapper;
    private final TransactionReceiptMapper receiptMapper;
    private final BookMapper bookMapper;
    private final CurrentBookService currentBookService;
    private final ReceiptStorage storage;
    private final ShareLinkResolver shareLinkResolver;

    @Override
    @Transactional(readOnly = true)
    public List<ReceiptResp> list(Long transactionId) {
        Long bookId = currentBookService.currentBookId();
        requireOwnedTransaction(transactionId, bookId);
        return receiptMapper.selectByTransaction(transactionId).stream()
                .map(ReceiptResp::from)
                .toList();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ReceiptResp upload(Long transactionId, String originalName, byte[] bytes, String title) {
        // 归档账本只读：写操作统一走 writableBookId
        Long bookId = currentBookService.writableBookId();
        Transaction transaction = requireOwnedTransaction(transactionId, bookId);
        return store(bookId, transaction, originalName, bytes, title);
    }

    @Override
    public ReceiptImportResp importZip(Long transactionId, String filename, byte[] bytes) {
        Long bookId = currentBookService.writableBookId();
        Transaction transaction = requireOwnedTransaction(transactionId, bookId);

        var dir = ZipExtractor.extract(bytes);
        try {
            int success = 0;
            var failed = new java.util.ArrayList<ReceiptImportResp.FailedFile>();
            for (var file : ZipExtractor.files(dir)) {
                String relative = ZipExtractor.relative(dir, file);
                String lower = relative.toLowerCase(java.util.Locale.ROOT);
                // 只认图片；包里的说明文件/txt 直接报一行，不静默忽略（用户需要知道它没被导入）
                if (!(lower.endsWith(".jpg") || lower.endsWith(".jpeg")
                        || lower.endsWith(".png") || lower.endsWith(".webp"))) {
                    failed.add(new ReceiptImportResp.FailedFile(relative, "只支持 jpg / png / webp 图片"));
                    continue;
                }
                try {
                    String name = file.getFileName().toString();
                    store(bookId, transaction, name, java.nio.file.Files.readAllBytes(file),
                            ReceiptStorage.defaultTitle(name));
                    success++;
                } catch (BusinessException e) {
                    failed.add(new ReceiptImportResp.FailedFile(relative, e.getMessage()));
                } catch (IOException e) {
                    failed.add(new ReceiptImportResp.FailedFile(relative, "读取文件失败"));
                }
            }
            log.info("票据压缩包导入：bookId={}, transactionId={}, 文件={}, 成功 {} 张，失败 {} 张",
                    bookId, transactionId, filename, success, failed.size());
            return new ReceiptImportResp(success, failed);
        } finally {
            ZipExtractor.deleteQuietly(dir);
        }
    }

    /** 落盘 + 插库（upload 与 zip 导入共用；调用方负责归属校验与归档判断） */
    private ReceiptResp store(Long bookId, Transaction transaction, String originalName, byte[] bytes, String title) {
        Long transactionId = transaction.getId();
        // 类型按魔数识别（不看扩展名），顺便把 SVG 这类能带脚本的挡在门外
        ReceiptStorage.ImageType type = ReceiptStorage.detect(bytes);
        if (bytes.length > ReceiptStorage.MAX_FILE_BYTES) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "单张票据不能超过 10MB");
        }
        if (receiptMapper.countByTransaction(transactionId) >= ReceiptStorage.MAX_PER_TRANSACTION) {
            throw new BusinessException(ResultCode.BAD_REQUEST,
                    "单笔最多 " + ReceiptStorage.MAX_PER_TRANSACTION + " 张票据，删掉几张再传");
        }
        long used = receiptMapper.sumSizeByUser(SecurityUtil.getUserId());
        if (used + bytes.length > ReceiptStorage.MAX_BYTES_PER_USER) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "票据总容量已满（500MB），请先清理一些旧票据");
        }

        Book book = bookMapper.selectById(bookId);
        String relative = ReceiptStorage.relativePath(
                ReceiptStorage.bookDirName(bookId, book == null ? null : book.getName()),
                transaction.getTransDate(), type.ext());

        TransactionReceipt receipt = new TransactionReceipt();
        receipt.setTransactionId(transactionId);
        receipt.setTitle(normalizeTitle(title, originalName));
        receipt.setOriginalName(truncate(originalName, 255));
        receipt.setStoredPath(relative);
        receipt.setContentType(type.contentType());
        receipt.setSizeBytes((long) bytes.length);
        // 先写文件再插库：插库失败会回滚，多出来的孤儿文件由每日清理兜底；
        // 反过来（先插库后写盘）一旦写盘失败，库里会留一条打不开的记录。
        storage.write(relative, bytes);
        receiptMapper.insert(receipt);

        log.info("上传票据：bookId={}, transactionId={}, receiptId={}, type={}, size={}",
                bookId, transactionId, receipt.getId(), type.contentType(), bytes.length);
        return ReceiptResp.from(receipt);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void rename(Long transactionId, Long receiptId, String title) {
        Long bookId = currentBookService.writableBookId();
        requireOwnedTransaction(transactionId, bookId);
        TransactionReceipt receipt = requireOwnedReceipt(transactionId, receiptId);

        String value = title == null ? "" : title.trim();
        if (!StringUtils.hasText(value)) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "票据标题不能为空");
        }
        receiptMapper.update(null, Wrappers.<TransactionReceipt>update()
                .eq("id", receipt.getId())
                .set("title", truncate(value, 30)));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long transactionId, Long receiptId) {
        Long bookId = currentBookService.writableBookId();
        requireOwnedTransaction(transactionId, bookId);
        TransactionReceipt receipt = requireOwnedReceipt(transactionId, receiptId);

        receiptMapper.deleteById(receipt.getId());
        // 先删库再删文件：库记录没了，即使文件残留也只是孤儿，扫一遍就能清掉
        storage.deleteQuietly(receipt.getStoredPath());
        log.info("删除票据：bookId={}, transactionId={}, receiptId={}", bookId, transactionId, receiptId);
    }

    @Override
    @Transactional(readOnly = true)
    public ReceiptFile load(Long transactionId, Long receiptId) {
        Long bookId = currentBookService.currentBookId();
        requireOwnedTransaction(transactionId, bookId);
        TransactionReceipt receipt = requireOwnedReceipt(transactionId, receiptId);
        return toFile(receipt);
    }

    @Override
    @Transactional(readOnly = true)
    public ReceiptFile loadPublic(String shareToken, Long receiptId) {
        // token 有效 + 账本成员：两条任一不满足都 404（不区分「存在但无权」，避免枚举）
        var link = shareLinkResolver.requireValid(shareToken);
        List<Long> bookIds = shareLinkResolver.bookIds(link.getId());

        TransactionReceipt receipt = receiptId == null ? null : receiptMapper.selectById(receiptId);
        if (receipt == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "票据不存在");
        }
        Transaction transaction = transactionMapper.selectById(receipt.getTransactionId());
        // selectById 自动带 deleted=0：流水进回收站后，公示页也看不到它的票据
        if (transaction == null || !bookIds.contains(transaction.getBookId())) {
            throw new BusinessException(ResultCode.NOT_FOUND, "票据不存在");
        }
        // 账本可能已被软删（进回收站）→ selectById 返回 null
        if (bookMapper.selectById(transaction.getBookId()) == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "票据不存在");
        }
        return toFile(receipt);
    }

    @Override
    public void purgeBookQuietly(Long bookId, String bookName) {
        receiptMapper.hardDeleteByBook(bookId);
        storage.deleteBookDirQuietly(bookId, bookName);
    }

    @Override
    public int purgeOrphanFiles(java.time.Duration minAge) {
        // 库里全部相对路径一次取回：磁盘上有、这里没有的就是孤儿
        var keep = new java.util.HashSet<>(receiptMapper.selectAllStoredPaths());
        int removed = storage.pruneOrphans(keep, minAge);
        if (removed > 0) {
            log.info("清理孤儿票据文件：{} 个（库里 {} 条记录）", removed, keep.size());
        }
        return removed;
    }

    /* ---------- 内部 ---------- */

    private Transaction requireOwnedTransaction(Long transactionId, Long bookId) {
        Transaction transaction = transactionId == null ? null : transactionMapper.selectById(transactionId);
        if (transaction == null || !transaction.getBookId().equals(bookId)) {
            throw new BusinessException(ResultCode.NOT_FOUND, "流水不存在");
        }
        return transaction;
    }

    private TransactionReceipt requireOwnedReceipt(Long transactionId, Long receiptId) {
        TransactionReceipt receipt = receiptId == null ? null : receiptMapper.selectById(receiptId);
        // 必须再比一次 transactionId：只查票据 id 就能拿到别人流水的票据
        if (receipt == null || !receipt.getTransactionId().equals(transactionId)) {
            throw new BusinessException(ResultCode.NOT_FOUND, "票据不存在");
        }
        return receipt;
    }

    private ReceiptFile toFile(TransactionReceipt receipt) {
        Path path = storage.resolve(receipt.getStoredPath());
        if (!Files.isReadable(path)) {
            log.warn("票据文件缺失：receiptId={}, path={}", receipt.getId(), receipt.getStoredPath());
            throw new BusinessException(ResultCode.NOT_FOUND, "票据文件已丢失");
        }
        String filename = receipt.getTitle() + "." + extOf(receipt.getContentType());
        return new ReceiptFile(path, receipt.getContentType(), filename);
    }

    private static String extOf(String contentType) {
        return switch (contentType == null ? "" : contentType) {
            case "image/png" -> "png";
            case "image/webp" -> "webp";
            default -> "jpg";
        };
    }

    private static String normalizeTitle(String title, String originalName) {
        if (StringUtils.hasText(title)) {
            return truncate(title.trim(), 30);
        }
        return ReceiptStorage.defaultTitle(originalName);
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.length() <= max ? trimmed : trimmed.substring(0, max);
    }
}