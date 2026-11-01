package com.bookkeep.service;

/**
 * 当前账本的唯一入口。
 * <p>所有按账本隔离的查询都从这里取 bookId：**前端永远不传 book_id**，
 * 后端从 {@code user.current_book_id} 推导（PLAN §2.3）。
 * <p>做成独立 Bean 而不是每个 Service 各写一遍，是为了让「越权检查」只有一个实现点：
 * 这里除了取 id，还会校验这本账本确实属于当前用户。
 */
public interface CurrentBookService {

    /**
     * @return 当前登录用户的账本 id
     * @throws com.bookkeep.common.exception.BusinessException 未登录（401）/ 用户已注销（401）/ 没有账本（400）
     */
    Long currentBookId();
}