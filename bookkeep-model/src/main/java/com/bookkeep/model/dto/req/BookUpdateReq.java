package com.bookkeep.model.dto.req;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

/**
 * 修改账本：改名 + （可选）改学期截止日期，一次提交。
 * <p>{@code semesterEnd} 为 null 表示**不改**（不是清空）：想清空就传空？——不提供清空，
 * 学期截止日期只是展示用的提示，改错直接改成正确日期即可，没必要为「清空」多开一种语义。
 *
 * @param name        账本名，≤50
 * @param semesterEnd 学期截止日期；null = 保持原值
 */
public record BookUpdateReq(

        @NotBlank(message = "账本名不能为空")
        @Size(max = 50, message = "账本名不能超过 50 个字符")
        String name,

        LocalDate semesterEnd
) {
}