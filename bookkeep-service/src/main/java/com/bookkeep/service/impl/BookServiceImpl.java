package com.bookkeep.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.bookkeep.common.exception.BusinessException;
import com.bookkeep.common.result.ResultCode;
import com.bookkeep.common.util.SecurityUtil;
import com.bookkeep.model.dto.req.BookCreateReq;
import com.bookkeep.model.dto.req.BookUpdateReq;
import com.bookkeep.model.dto.resp.BookResp;
import com.bookkeep.model.dto.resp.ShareLinkCreatedResp;
import com.bookkeep.model.dto.resp.BookTrashResp;
import com.bookkeep.model.dto.resp.CategoryTemplateResp;
import com.bookkeep.model.dto.resp.ShareLinkResp;
import com.bookkeep.model.entity.Book;
import com.bookkeep.model.entity.ShareLink;
import com.bookkeep.model.entity.ShareLinkBook;
import com.bookkeep.model.entity.Category;
import com.bookkeep.model.entity.User;
import com.bookkeep.service.BookService;
import com.bookkeep.service.CategoryTemplateService;
import com.bookkeep.service.ReceiptService;
import com.bookkeep.service.constant.CategoryPresets;
import com.bookkeep.service.mapper.BookMapper;
import com.bookkeep.service.mapper.CategoryMapper;
import com.bookkeep.service.mapper.ShareLinkBookMapper;
import com.bookkeep.service.mapper.ShareLinkMapper;
import com.bookkeep.service.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * 账本业务。
 * <p>归属一律按 book.user_id 校验（别人的账本返回「不存在」）；
 * 删除 = 进回收站 15 天；至少留一本。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BookServiceImpl implements BookService {

    /** 每用户账本上限 */
    private static final int MAX_BOOKS = 10;

    /** 回收站保留天数 */
    public static final int RETENTION_DAYS = 15;

    /** 公示链接有效期 */
    public static final int SHARE_LINK_DAYS = 3;

    private final BookMapper bookMapper;
    private final CategoryMapper categoryMapper;
    private final UserMapper userMapper;
    private final CategoryTemplateService categoryTemplateService;
    private final ShareLinkMapper shareLinkMapper;
    private final ShareLinkBookMapper shareLinkBookMapper;
    private final ReceiptService receiptService;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public List<BookResp> list() {
        // 顺手清过期的：本机不保证 7×24 开着，定时任务可能在停机期间错过
        purgeExpired();
        return doList();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public BookResp create(BookCreateReq req) {
        User user = requireUser();
        long count = bookMapper.selectCount(Wrappers.<Book>query().eq("user_id", user.getId()));
        if (count >= MAX_BOOKS) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "账本最多 " + MAX_BOOKS + " 本，先删掉不用的");
        }

        // 先取条目再落库：模板 id 不合法（别人的 / 不存在）时直接抛，不留半本账本
        List<CategoryTemplateResp.Item> items = categoryTemplateService.itemsOf(req.templateId());
        // null / 0 都是内置模板，落库存 NULL（列注释：NULL = 内置）
        Long templateId = req.templateId() == null || req.templateId() == CategoryPresets.BUILTIN_ID
                ? null : req.templateId();

        Book book = new Book();
        book.setUserId(user.getId());
        book.setName(normalizeName(req.name()));
        book.setType(CategoryPresets.BOOK_CLASS);
        book.setCategoryTemplateId(templateId);
        book.setSemesterEnd(req.semesterEnd());
        book.setArchived(0);
        // 公示 token 不在这里发：必须由用户点「分享」显式生成，链接才有 3 天有效期可言
        bookMapper.insert(book);

        // 与注册同一套写法：逐条 insert（同一事务、同一 SqlSession），不要用静态 Db.saveBatch
        for (CategoryTemplateResp.Item item : items) {
            Category category = new Category();
            category.setBookId(book.getId());
            category.setName(item.name());
            category.setType(item.type());
            // 记下来源：以后改模板时，这一本账本的分类才能跟着改名/删除
            category.setTemplateItemId(item.id());
            category.setIcon(item.icon());
            category.setColor(item.color());
            category.setSortOrder(item.sortOrder());
            categoryMapper.insert(category);
        }

        // 新建即切换：用户刚建完账本，下一屏就该是它
        updateCurrentBookId(user.getId(), book.getId());
        log.info("新建账本：userId={}, bookId={}, name={}, templateId={}, categories={}",
                user.getId(), book.getId(), book.getName(), templateId, items.size());
        return BookResp.from(book, book.getId());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ShareLinkResp share(Long bookId) {
        // 单本分享 = 「只选了一本」的批量分享，保证两条路径只有一套逻辑
        ShareLinkCreatedResp created = shareBatch(List.of(bookId));
        return new ShareLinkResp(created.token(), created.expiresAt());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ShareLinkCreatedResp shareBatch(List<Long> bookIds) {
        User user = requireUser();
        List<Long> unique = bookIds.stream().distinct().toList();
        // 先全部校验归属：一条不是自己的就整批 404（和批量删除同一套口径）
        List<Book> books = new ArrayList<>(unique.size());
        for (Long bookId : unique) {
            books.add(requireOwned(bookId, user.getId()));
        }

        LocalDateTime now = LocalDateTime.now();
        // 每次分享都换新链接：旧链接立即失效（泄漏时再点一次「分享」就能掐掉它）
        shareLinkMapper.deleteBookshipsByUser(user.getId());
        shareLinkMapper.deleteByUser(user.getId());

        ShareLink link = new ShareLink();
        link.setUserId(user.getId());
        link.setToken(UUID.randomUUID().toString().replace("-", ""));
        link.setExpiresAt(now.plusDays(SHARE_LINK_DAYS));
        shareLinkMapper.insert(link);

        int order = 1;
        for (Book book : books) {
            ShareLinkBook member = new ShareLinkBook();
            member.setShareLinkId(link.getId());
            member.setBookId(book.getId());
            member.setSortOrder(order++);
            shareLinkBookMapper.insert(member);
        }

        log.info("生成公示链接：userId={}, 账本={}, 有效期至 {}", user.getId(), unique, link.getExpiresAt());
        return new ShareLinkCreatedResp(link.getToken(), link.getExpiresAt(), toSharedBooks(books));
    }

    private static List<ShareLinkCreatedResp.SharedBook> toSharedBooks(List<Book> books) {
        return books.stream()
                .map(book -> new ShareLinkCreatedResp.SharedBook(book.getId(), book.getName()))
                .toList();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public BookResp update(Long id, BookUpdateReq req) {
        User user = requireUser();
        Book book = requireOwned(id, user.getId());

        String name = normalizeName(req.name());
        // 只 set 会变的列：更新整行容易把 shareToken 覆盖成 null
        var update = Wrappers.<Book>update()
                .eq("id", id)
                .set("name", name);
        // semesterEnd 为 null 表示「不改」，不是清空——学期截止日期只是提示，改错直接改成正确日期
        if (req.semesterEnd() != null) {
            update.set("semester_end", req.semesterEnd());
            book.setSemesterEnd(req.semesterEnd());
        }
        bookMapper.update(null, update);

        book.setName(name);
        log.info("修改账本：userId={}, bookId={}, name={}, semesterEnd={}",
                user.getId(), id, name, book.getSemesterEnd());
        return BookResp.from(book, user.getCurrentBookId());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public BookResp setArchived(Long id, boolean archived) {
        User user = requireUser();
        Book book = requireOwned(id, user.getId());
        int flag = archived ? 1 : 0;

        // 幂等：已经是目标状态就直接返回，前端重复点不会报错
        if (book.getArchived() != null && book.getArchived() == flag) {
            return BookResp.from(book, user.getCurrentBookId());
        }

        bookMapper.update(null, Wrappers.<Book>update()
                .eq("id", id)
                .eq("user_id", user.getId())
                .set("archived", flag));
        book.setArchived(flag);
        log.info("{}账本：userId={}, bookId={}", archived ? "归档" : "取消归档", user.getId(), id);
        return BookResp.from(book, user.getCurrentBookId());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public List<BookResp> switchCurrent(Long id) {
        User user = requireUser();
        requireOwned(id, user.getId());
        updateCurrentBookId(user.getId(), id);
        log.info("切换当前账本：userId={}, bookId={}", user.getId(), id);
        return doList();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        User user = requireUser();
        requireOwned(id, user.getId());

        long active = bookMapper.selectCount(Wrappers.<Book>query().eq("user_id", user.getId()));
        if (active <= 1) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "至少保留一本账本，先新建一本再删这本");
        }

        // 不能用 deleteById：它只写 deleted=1，不会写 deleted_at，回收站就算不出到期时间
        bookMapper.update(null, Wrappers.<Book>update()
                .eq("id", id)
                .set("deleted", 1)
                .set("deleted_at", LocalDateTime.now()));

        if (Objects.equals(user.getCurrentBookId(), id)) {
            // 删的是当前账本：重指到剩下最早的一本，否则所有接口都会「没有当前账本」
            Book next = bookMapper.selectOne(Wrappers.<Book>query()
                    .eq("user_id", user.getId())
                    .orderByAsc("id")
                    .last("LIMIT 1"));
            updateCurrentBookId(user.getId(), next.getId());
        }
        log.info("账本移入回收站：userId={}, bookId={}", user.getId(), id);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public List<BookResp> deleteBatch(List<Long> ids) {
        User user = requireUser();
        List<Long> unique = ids.stream().distinct().toList();

        // 先全部校验归属：一条不是自己的就整批拒绝，避免「删了一半才发现」
        for (Long id : unique) {
            requireOwned(id, user.getId());
        }

        long active = bookMapper.selectCount(Wrappers.<Book>query().eq("user_id", user.getId()));
        if (active - unique.size() < 1) {
            throw new BusinessException(ResultCode.BAD_REQUEST,
                    "至少保留一本账本，先新建一本再删这些（本次选了 " + unique.size() + " 本）");
        }

        // 不能用 deleteById：它只写 deleted=1，不写 deleted_at，回收站就算不出到期时间
        bookMapper.update(null, Wrappers.<Book>update()
                .eq("user_id", user.getId())
                .in("id", unique)
                .set("deleted", 1)
                .set("deleted_at", LocalDateTime.now()));

        if (user.getCurrentBookId() != null && unique.contains(user.getCurrentBookId())) {
            // 删掉的是当前账本：重指到剩下最早的一本，否则所有接口都会「没有当前账本」
            Book next = bookMapper.selectOne(Wrappers.<Book>query()
                    .eq("user_id", user.getId())
                    .orderByAsc("id")
                    .last("LIMIT 1"));
            updateCurrentBookId(user.getId(), next.getId());
        }
        log.info("批量移入回收站：userId={}, bookIds={}", user.getId(), unique);
        return doList();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public List<BookTrashResp> trash() {
        purgeExpired();
        Long userId = SecurityUtil.getUserId();
        return bookMapper.selectTrash(userId).stream()
                .map(book -> BookTrashResp.from(book, RETENTION_DAYS))
                .toList();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public BookResp restore(Long id) {
        Long userId = SecurityUtil.getUserId();
        // 恢复必须走原生 SQL：MP 的逻辑删除会让 update 自动带上 deleted=0，永远改不到已删的行
        if (bookMapper.restore(id, userId) == 0) {
            throw new BusinessException(ResultCode.NOT_FOUND, "账本不存在或不在回收站");
        }
        User user = requireUser();
        Book book = bookMapper.selectById(id);
        log.info("从回收站恢复账本：userId={}, bookId={}", userId, id);
        return BookResp.from(book, user.getCurrentBookId());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void purge(Long id) {
        Long userId = SecurityUtil.getUserId();
        // 只允许删「自己回收站里」的：用一句手写查询定身份，活着的一律 404（防误删在用账本）
        if (bookMapper.selectTrashedId(id, userId) == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "账本不存在或不在回收站");
        }
        hardDeleteBookCascade(id);
        log.info("彻底删除账本：userId={}, bookId={}（连同分类与流水）", userId, id);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int purgeBatch(List<Long> ids) {
        Long userId = SecurityUtil.getUserId();
        List<Long> unique = ids.stream().distinct().toList();

        // 先整批校验：有一条不在「自己的回收站里」就整批拒绝，绝不出现「删一半」
        for (Long id : unique) {
            if (bookMapper.selectTrashedId(id, userId) == null) {
                throw new BusinessException(ResultCode.NOT_FOUND, "账本不存在或不在回收站");
            }
        }
        for (Long id : unique) {
            hardDeleteBookCascade(id);
        }
        log.info("批量彻底删除账本：userId={}, bookIds={}（连同分类与流水）", userId, unique);
        return unique.size();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int purgeExpired() {
        LocalDateTime deadline = LocalDateTime.now().minusDays(RETENTION_DAYS);
        List<Long> expired = bookMapper.selectExpiredIds(deadline);
        for (Long bookId : expired) {
            hardDeleteBookCascade(bookId);
        }
        if (!expired.isEmpty()) {
            log.info("回收站到期清理：彻底删除 {} 本账本（含分类与流水），bookIds={}", expired.size(), expired);
        }
        return expired.size();
    }

    /**
     * 彻底删除一本账本的全部数据：流水 → 分类 → 账本 → 公示链接成员 → 空链接。
     * <p>顺序从子表往父表，将来真加了外键也不会报错；链接成员必须清掉，
     * 否则公示链接里会留一个永远打不开的账本 id。
     */
    private void hardDeleteBookCascade(Long bookId) {
        // 目录名要账本名，必须在删 book 之前取；否则只能用 book_{id} 兜底，图片目录会变成孤儿
        Book book = bookMapper.selectById(bookId);
        // 票据必须**先**删：hardDeleteByBook 是 join `transaction` 定位的，
        // 先把流水删掉它就一行都匹配不到，记录会永远留成孤儿（文件也再没人回收）
        receiptService.purgeBookQuietly(bookId, book == null ? null : book.getName());
        bookMapper.hardDeleteTransactions(bookId);
        bookMapper.hardDeleteCategories(bookId);
        bookMapper.hardDeleteBook(bookId);
        shareLinkMapper.deleteBookshipsByBook(bookId);
        shareLinkMapper.deleteOrphanLinks();
    }

    private List<BookResp> doList() {
        User user = requireUser();
        return bookMapper.selectList(Wrappers.<Book>query()
                        .eq("user_id", user.getId())
                        .orderByAsc("id"))
                .stream()
                .map(book -> BookResp.from(book, user.getCurrentBookId()))
                .toList();
    }

    private User requireUser() {
        User user = userMapper.selectById(SecurityUtil.getUserId());
        if (user == null) {
            // 注销后旧 token 仍可能在有效期内（过滤器不查库），这里要拦住
            throw new BusinessException(ResultCode.UNAUTHORIZED, "用户不存在或已注销");
        }
        return user;
    }

    private Book requireOwned(Long id, Long userId) {
        Book book = bookMapper.selectById(id);
        if (book == null || !book.getUserId().equals(userId)) {
            throw new BusinessException(ResultCode.NOT_FOUND, "账本不存在");
        }
        return book;
    }

    private void updateCurrentBookId(Long userId, Long bookId) {
        userMapper.update(null, Wrappers.<User>update()
                .eq("id", userId)
                .set("current_book_id", bookId));
    }

    private String normalizeName(String rawName) {
        String name = rawName.trim();
        // @NotBlank 挡不住全空格，trim 之后才是最终值
        if (name.isEmpty()) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "账本名不能为空");
        }
        return name;
    }
}