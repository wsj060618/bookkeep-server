package com.bookkeep.model.dto.req;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

/**
 * 新建账本的请求体：账本名 + 分类模板 + 可选的学期截止日期。
 *
 * @param name        账本名，≤50
 * @param templateId  分类模板 id；**null 或 0 = 内置「班级班费」**（与注册时预置的 11 条同一套）
 * @param semesterEnd 学期截止日期（可空）；「一个学期 = 一本账本」，学期结束就归档成只读
 */
public record BookCreateReq(

        @NotBlank(message = "账本名不能为空")
        @Size(max = 50, message = "账本名不能超过 50 个字符")
        String name,

        Long templateId,

        LocalDate semesterEnd
) {
}