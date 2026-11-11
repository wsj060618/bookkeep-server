package com.bookkeep.service;

import com.bookkeep.model.dto.resp.ReceiptResp;

import java.nio.file.Path;
import java.util.List;

/**
 * 流水票据。每个方法都先确认流水属于当前账本（只按 id 查会越权）；
 * 写操作用 writableBookId()，归档账本只读。
 */
public interface ReceiptService {

    List<ReceiptResp> list(Long transactionId);

    /** bytes 而不是 MultipartFile：Service 不碰 Web 类型 */
    ReceiptResp upload(Long transactionId, String originalName, byte[] bytes, String title);

    void rename(Long transactionId, Long receiptId, String title);

    void delete(Long transactionId, Long receiptId);

    /** 单笔票据导入：逐张处理，失败不阻塞其它张 */
    com.bookkeep.model.dto.resp.ReceiptImportResp importZip(Long transactionId, String filename, byte[] bytes);

    /** {@code thumb=true} 时给 480px 缩略图；没有（或解码不了）就退回原图，绝不因为缩略图打不开图 */
    ReceiptFile load(Long transactionId, Long receiptId, boolean thumb);

    /** 公示页匿名取图，任一校验不过一律 404 */
    ReceiptFile loadPublic(String shareToken, Long receiptId, boolean thumb);

    void purgeBookQuietly(Long bookId, String bookName);

    /** minAge 是为了不误删「已落盘、还没入库」的那张 */
    int purgeOrphanFiles(java.time.Duration minAge);

    /** Controller 负责输出，Service 只给路径 */
    record ReceiptFile(Path path, String contentType, String filename) {
    }
}