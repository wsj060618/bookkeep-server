package com.bookkeep.model.dto.req;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 新增 / 修改分类。修改时不能改 type（Service 校验）。
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