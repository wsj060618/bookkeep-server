package com.bookkeep.model.dto.req;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 新建 / 修改分类模板的请求体。
 * <p>三种用法：
 * <ul>
 *   <li>{@code POST /api/category-templates} + items：从零新建一套模板</li>
 *   <li>{@code POST /api/category-templates/from-book}：只带 name，把当前账本的分类快照成模板</li>
 *   <li>{@code PUT /api/category-templates/{id}}：name 必填；items 非空时**整体替换**条目</li>
 * </ul>
 *
 * @param name  模板名，≤50，同一账号内唯一
 * @param items 模板条目；新建时必须非空，改名的 PUT 可以不传
 */
public record CategoryTemplateReq(

        @NotBlank(message = "模板名不能为空")
        @Size(max = 50, message = "模板名不能超过 50 个字符")
        String name,

        @Valid
        List<Item> items
) {

    /**
     * @param type  income / expense；由前端按「支出卡片 / 收入卡片」固定，不用用户再选
     * @param icon  图标语义 key，可空
     * @param color 颜色语义 key，可空
     */
    public record Item(

            @NotBlank(message = "分类名不能为空")
            @Size(max = 50, message = "分类名不能超过 50 个字符")
            String name,

            String type,

            String icon,

            String color
    ) {
    }
}