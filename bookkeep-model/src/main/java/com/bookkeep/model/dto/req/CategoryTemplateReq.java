package com.bookkeep.model.dto.req;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 新建（从当前账本快照）/ 重命名分类模板的请求体。
 * <p>只带名字：模板条目一律由「当前账本的分类」快照生成，不给前端直接传条目的入口，
 * 避免出现「模板里有个分类名当前账本没有」这种对不上的状态。
 *
 * @param name 模板名，≤50
 */
public record CategoryTemplateReq(

        @NotBlank(message = "模板名不能为空")
        @Size(max = 50, message = "模板名不能超过 50 个字符")
        String name
) {
}
