package com.bookkeep.model.dto.resp;

import com.bookkeep.model.entity.Category;

/**
 * 分类响应体。
 * <p>直接把 icon / color 的语义 key 给前端，由前端映射图标组件与颜色；
 * 后端不关心具体长什么样，换图标库不用改数据。
 */
public record CategoryResp(
        Long id,
        String name,
        String type,
        String icon,
        String color,
        Integer sortOrder
) {

    public static CategoryResp from(Category category) {
        return new CategoryResp(
                category.getId(),
                category.getName(),
                category.getType(),
                category.getIcon(),
                category.getColor(),
                category.getSortOrder()
        );
    }
}