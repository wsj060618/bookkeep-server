package com.bookkeep.service;

import com.bookkeep.model.dto.req.BookCreateReq;
import com.bookkeep.model.dto.req.BookUpdateReq;
import com.bookkeep.model.dto.resp.BookResp;
import com.bookkeep.model.dto.resp.ShareLinkCreatedResp;
import com.bookkeep.model.dto.resp.BookTrashResp;
import com.bookkeep.model.dto.resp.ShareLinkResp;

import java.util.List;

/**
 * 账本：增删改查 + 回收站。删除是软删，15 天内可恢复。
 * <p>其它业务仍然只认 user.current_book_id，切换账本不用通知它们。
 */
public interface BookService {

    List<BookResp> list();

    /** 按所选模板（null/0 = 内置）预置分类，并自动设为当前 */
    BookResp create(BookCreateReq req);

    /** 单本分享 = 只选一本的批量分享 */
    ShareLinkResp share(Long bookId);

    /** 一条链接包含选中的全部账本，每次分享都换新 token */
    ShareLinkCreatedResp shareBatch(List<Long> bookIds);

    BookResp update(Long id, BookUpdateReq req);

    /** 归档 = 只读；重复调用幂等 */
    BookResp setArchived(Long id, boolean archived);

    List<BookResp> switchCurrent(Long id);

    /** 进回收站；至少保留一本 */
    void delete(Long id);

    List<BookResp> deleteBatch(List<Long> ids);

    List<BookTrashResp> trash();

    BookResp restore(Long id);

    void purge(Long id);

    int purgeBatch(List<Long> ids);

    /** 清理回收站里满 15 天的账本 */
    int purgeExpired();
}