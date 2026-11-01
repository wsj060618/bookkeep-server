package com.bookkeep.model.dto.req;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 新增 / 修改分类的请求体。
 * <p>新增时 type 必填；修改时 type 不可变更（改了会让该分类下历史流水的收支方向失真），
 * Service 会校验「传上来的 type 与库里一致」。
 *
 * @param name  分类名，≤50
 * @param type  income / expense
 * @param icon  图标语义 key（前端映射），可空
 * @param color 颜色语义 key（前端映射），可空
 */
public record CategoryReq(

        @NotBlank(message = "分类名不能为空")
        @Size(max = 50, message = "分类名不能超过 50 个字符")
        String name,

        @NotBlank(message = "收支类型不能为空")
        @Pattern(regexp = "income|expense", message = "收支类型只能是 income 或 expense")
        String type,

        @Size(max = 100, message = "图标 key 不能超过 100 个字符")
        String icon,

        @Size(max = 20, message = "颜色 key 不能超过 20 个字符")
        String color
) {
}