package com.bookkeep.service;

import com.bookkeep.model.dto.req.BookCreateReq;
import com.bookkeep.model.dto.req.BookUpdateReq;
import com.bookkeep.model.dto.resp.BookResp;
import com.bookkeep.model.dto.resp.BookShareResp;
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
    /** 新建账本：按所选模板（null/0 = 内置）预置分类，并自动设为当前 */
    BookResp create(BookCreateReq req);

    /** 生成 / 重置公示链接：每次调用都换新 token，有效期 3 天，旧链接立即失效 */
    ShareLinkResp share(Long bookId);

    /**
     * 批量出链接（分享弹窗用）：**已有未过期链接的账本原样复用**，只给没有/已过期的生成新的。
     * <p>这样「只是想把链接都列出来」不会把已经发到班群的旧链接顶掉。
     */
    List<BookShareResp> shareBatch(List<Long> bookIds);

    /** 修改账本：改名 +（可选）改学期截止日期；归档账本也允许改这两项 */
    BookResp update(Long id, BookUpdateReq req);

    /**
     * 归档 / 取消归档（学期结束 ↔ 重新开启）。
     * <p>归档后的账本**只读**：不能再记流水、不能增删改分类、不能应用模板；
     * 但改名、改截止日期、导出、公示页都照常。重复调用幂等。
     */
    BookResp setArchived(Long id, boolean archived);

    /** 切换当前账本，返回切换后的完整列表 */
    List<BookResp> switchCurrent(Long id);

    /** 删除 → 进回收站；至少保留一本 */
    void delete(Long id);

    /** 批量删除（进回收站）：至少保留一本；删到当前账本时自动切到剩下最早的一本 */
    List<BookResp> deleteBatch(List<Long> ids);

    /** 回收站列表，带「彻底删除时刻」 */
    List<BookTrashResp> trash();

    /** 从回收站恢复（不自动切成当前账本） */
    BookResp restore(Long id);

    /** 立即彻底删除回收站里的某一本（连同分类、流水）；只能删已在回收站的，不可恢复 */
    void purge(Long id);

    /** 批量彻底删除（回收站里的）；任何一条不在回收站就整批拒绝，返回删除本数 */
    int purgeBatch(List<Long> ids);

    /** 彻底删除回收站里满 15 天的账本（连同分类、流水），返回清理本数 */
    int purgeExpired();
}