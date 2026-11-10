package com.bookkeep.model.dto.resp;

import java.util.List;

/** 分类模板（条目一起返回） */
public record CategoryTemplateResp(
        Long id,
        String name,
        boolean builtin,
        List<Item> items
) {

    /** id 是 category_template_item.id，内置投影为 null；保存模板时要原样带回，后端才知道是改名还是新增 */
    public record Item(
            Long id,
            String name,
            String type,
            String icon,
            String color,
            Integer sortOrder
    ) {
    }
}