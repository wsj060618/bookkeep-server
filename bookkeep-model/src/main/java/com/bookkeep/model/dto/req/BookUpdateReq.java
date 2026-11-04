package com.bookkeep.model.dto.req;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 修改账本信息（PLAN §5.2）。当前只有名字可改：不做新建 / 删除 / 切换账本。
 *
 * @param name 新账本名，≤50 字；改名会影响公示页标题，但不影响 shareToken
 */
public record BookUpdateReq(

        @NotBlank(message = "账本名不能为空")
        @Size(max = 50, message = "账本名不能超过 50 个字")
        String name
) {
}