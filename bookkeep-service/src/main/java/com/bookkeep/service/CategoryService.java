package com.bookkeep.service;

import com.bookkeep.model.dto.req.CategoryReq;
import com.bookkeep.model.dto.resp.CategoryResp;

import java.util.List;

/**
 * 分类业务（按当前账本隔离）。
 */
public interface CategoryService {

    /**
     * @param type income / expense，可空（空 = 不筛选）
     */
    List<CategoryResp> list(String type);

    CategoryResp create(CategoryReq req);

    CategoryResp update(Long id, CategoryReq req);

    /** 删除前会检查有没有流水引用它；有则抛业务异常（不级联删流水）。 */
    void delete(Long id);
}