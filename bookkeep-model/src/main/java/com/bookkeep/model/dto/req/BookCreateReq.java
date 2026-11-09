package com.bookkeep.model.dto.req;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 新建账本的请求体：账本名 + **用哪套分类模板**。
 * <p>为什么不复用 {@link BookUpdateReq}：改名没有「模板」这个维度，
 * 混在一起会让「改名时传 templateId」变成静默无效的字段。
 *
 * @param name       账本名，≤50
 * @param templateId 分类模板 id；**null 或 0 = 内置「班级班费」**（与注册时预置的 13 条同一套）
 */
public record BookCreateReq(

        @NotBlank(message = "账本名不能为空")
        @Size(max = 50, message = "账本名不能超过 50 个字符")
        String name,

        Long templateId
) {
}