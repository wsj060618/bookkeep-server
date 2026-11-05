package com.bookkeep.service;

import com.bookkeep.model.dto.req.BookUpdateReq;
import com.bookkeep.model.dto.resp.BookResp;
import com.bookkeep.model.dto.resp.BookTrashResp;
import com.bookkeep.model.dto.resp.ShareLinkResp;

import java.util.List;

/**
 * 账本业务：多账本增删改查 + 回收站（PLAN §5.2）。
 * <p>删除是「进回收站」而不是直接抹掉：15 天内可恢复，到期才连同分类、流水彻底删除。
 * <p>分类 / 流水 / 统计 / 导出仍然只认 {@code user.current_book_id}，切换账本不需要改动它们。
 */
public interface BookService {

    /** 我的账本（不含回收站），按创建顺序 */
    List<BookResp> list();

    /** 新建账本（预置 13 条分类），并自动切为当前 */
    BookResp create(BookUpdateReq req);

    /** 生成 / 重置公示链接：每次调用都换新 token，有效期 3 天，旧链接立即失效 */
    ShareLinkResp share(Long bookId);

    /** 重命名自己的账本 */
    BookResp rename(Long id, BookUpdateReq req);

    /** 切换当前账本，返回切换后的完整列表 */
    List<BookResp> switchCurrent(Long id);

    /** 删除 → 进回收站；至少保留一本 */
    void delete(Long id);

    /** 回收站列表，带「彻底删除时刻」 */
    List<BookTrashResp> trash();

    /** 从回收站恢复（不自动切成当前账本） */
    BookResp restore(Long id);

    /** 彻底删除回收站里满 15 天的账本（连同分类、流水），返回清理本数 */
    int purgeExpired();
}