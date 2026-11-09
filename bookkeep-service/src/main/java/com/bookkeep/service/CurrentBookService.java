package com.bookkeep.service;

/**
 * 当前账本的唯一入口。
 * <p>所有按账本隔离的查询与写入都通过它取 bookId，**前端永远不传 book_id**，
 * 因此从 {@code user.current_book_id} 推导（PLAN §2.3）。
 * <p>不做成多个 Bean、不让每个 Service 各写一遍，是因为「越权」只有一处实现点。
 * <p>读用 {@link #currentBookId()}，**写必须用 {@link #writableBookId()}**：归档账本只读。
 */
public interface CurrentBookService {

    /**
     * @return 当前登录用户的账本 id
     * @throws com.bookkeep.common.exception.BusinessException 未登录（401）/ 用户已注销（401）/ 没有账本（400）
     */
    Long currentBookId();

    /**
     * 当前账本 id，且账本**没归档**（归档 = 学期结束的只读账本）。
     * <p>记账 / 增删改分类 / 应用模板这类写操作都用这个，归档时返回业务 400。
     */
    Long writableBookId();
}