package com.bookkeep.model.dto.req;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 修改票据标题。空标题不允许（留空等于没名字，展示时没法区分两张图）。
 */
public record ReceiptRenameReq(

        @NotBlank(message = "票据标题不能为空")
        @Size(max = 30, message = "票据标题不能超过 30 个字")
        String title
) {
}