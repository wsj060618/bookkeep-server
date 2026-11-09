package com.bookkeep.model.dto.resp;

import java.util.List;

/**
 * 分类模板响应体（模板库列表用，条目一起返回——13 条量级，省一次请求）。
 *
 * @param id      模板 id；**内置模板固定为 0**（不入库，内容来自代码常量）
 * @param name    模板名
 * @param builtin 是否内置（内置的不能改名/删除，只能应用）
 * @param items   模板条目，按 sortOrder 升序
 */
public record CategoryTemplateResp(
        Long id,
        String name,
        boolean builtin,
        List<Item> items
) {

    public record Item(
            String name,
            String type,
            String icon,
            String color,
            Integer sortOrder
    ) {
    }
}
