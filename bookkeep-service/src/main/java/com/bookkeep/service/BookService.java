package com.bookkeep.service;

import com.bookkeep.model.dto.req.BookUpdateReq;
import com.bookkeep.model.dto.resp.BookResp;

/**
 * 账本业务（当前只有重命名，见 PLAN §5.2）。
 */
public interface BookService {

    /** 重命名自己的账本；不是自己的账本一律按「不存在」处理。 */
    BookResp rename(Long id, BookUpdateReq req);
}