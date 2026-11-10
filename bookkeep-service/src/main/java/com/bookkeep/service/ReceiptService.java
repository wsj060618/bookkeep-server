package com.bookkeep.service;

import com.bookkeep.model.dto.resp.ReceiptResp;

import java.nio.file.Path;
import java.util.List;

/**
 * 流水票据（PLAN §10.9）。
 * <p>所有方法第一件事都是「确认这笔流水属于当前账本」：{@code transactionId} 来自前端，
 * 只按 id 查会被越权读到别人的图。写操作统一用 {@link CurrentBookService#writableBookId()}（归档只读）。
 */
public interface ReceiptService {

    /** 该笔流水的票据列表（不含字节） */
    List<ReceiptResp> list(Long transactionId);

    /**
     * 上传一张票据。
     *
     * @param originalName 上传时的文件名（只用来取默认标题与展示，不参与路径）
     * @param bytes        文件内容（Service 不收 MultipartFile，便于单测）
     * @param title        用户起的标题，空则回落 {@link com.bookkeep.service.support.ReceiptStorage#defaultTitle}
     */
    ReceiptResp upload(Long transactionId, String originalName, byte[] bytes, String title);

    void rename(Long transactionId, Long receiptId, String title);

    void delete(Long transactionId, Long receiptId);

    /** 登录态取单张（账本归属校验） */
    ReceiptFile load(Long transactionId, Long receiptId);

    /** 公示页匿名取图：token 有效 + 账本未删 + 流水未删 + 票据归属该账本，任一不满足一律 404 */
    ReceiptFile loadPublic(String shareToken, Long receiptId);

    /** 账本彻底删除时清掉票据记录与整本目录（文件删失败只记日志） */
    void purgeBookQuietly(Long bookId, String bookName);

    /**
     * 清理孤儿文件：磁盘上有、库里没有记录的图片（先落盘后插库，插库失败就会留下）。
     *
     * @param minAge 只清理最后修改时间早于此刻往前 minAge 的文件，避免删掉「正在上传、还没入库」的那张
     * @return 删除的文件数
     */
    int purgeOrphanFiles(java.time.Duration minAge);

    /** 票据文件的落点（Controller 负责流式输出，Service 不碰 HttpServletResponse） */
    record ReceiptFile(Path path, String contentType, String filename) {
    }
}