package com.bookkeep.model.dto.req;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 新建 / 修改分类模板。
 * <p>新建带 items；from-book 只带 name；PUT 时 items 不传 = 只改名。
 */
public record CategoryTemplateReq(

        @NotBlank(message = "模板名不能为空")
        @Size(max = 50, message = "模板名不能超过 50 个字符")
        String name,

        @Valid
        List<Item> items
) {

    /**
     * @param id    已有条目的 id（`category_template_item.id`），新增条目传 null。
     *              **带上它才能区分「改名」和「删旧建新」**：改名同步到账本分类（流水不丢），
     *              删旧建新会被流水挡住。
     * @param type  income / expense；由前端按「支出卡片 / 收入卡片」固定，不用用户再选
     * @param icon  图标语义 key，可空
     * @param color 颜色语义 key，可空
     */
    public record Item(

            Long id,

            @NotBlank(message = "分类名不能为空")
            @Size(max = 50, message = "分类名不能超过 50 个字符")
            String name,

            String type,

            String icon,

            String color
    ) {
    }
}