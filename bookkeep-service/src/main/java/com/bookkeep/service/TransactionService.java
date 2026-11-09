package com.bookkeep.service;

import com.bookkeep.common.result.PageResult;
import com.bookkeep.model.dto.req.TransactionQuery;
import com.bookkeep.model.dto.req.TransactionReq;
import com.bookkeep.model.dto.resp.TransactionResp;

import java.util.List;

/**
 * 流水业务（按当前账本隔离）。
 * <p>业务规则（PLAN §5.5.1）：
 * <ul>
 *   <li>流水的 type 必须与所属分类一致，不信任前端传来的值</li>
 *   <li><strong>支出不能超过当前余额</strong>；修改时按「排除本条的余额」判断，避免自己挡自己</li>
 *   <li>删除是逻辑删除，余额随之恢复</li>
 * </ul>
 */
public interface TransactionService {

    PageResult<TransactionResp> page(TransactionQuery query);

    TransactionResp create(TransactionReq req);

    TransactionResp update(Long id, TransactionReq req);

    void delete(Long id);

    /** 批量删除（逻辑删除）；归档账本只读，返回实际删除条数 */
    int deleteBatch(List<Long> ids);

    /** 当前账本余额 = Σ收入 − Σ支出（不含已删除）。前端顶部「结余」用它。 */
    java.math.BigDecimal balance();
}