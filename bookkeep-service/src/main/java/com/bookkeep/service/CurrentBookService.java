package com.bookkeep.service;

/**
 * 当前账本：所有按账本隔离的读写都从这里取 bookId（前端永远不传 book_id）。
 * <p>读用 currentBookId()，写必须用 writableBookId()（归档账本只读）。
 */
public interface CurrentBookService {

    /** 未登录 / 已注销 → 401，没有账本 → 400 */
    Long currentBookId();

    /** 同上，但账本归档时抛 400 */
    Long writableBookId();
}