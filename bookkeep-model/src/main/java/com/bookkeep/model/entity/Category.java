package com.bookkeep.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("category")
public class Category {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long bookId;

    private String name;

    /** 收支类型：income / expense（常量见 {@code CategoryTemplate}）。 */
    private String type;

    /** 来自哪个模板条目（`category_template_item.id`）；NULL = 用户自建，不参与模板同步。 */
    private Long templateItemId;

    private String icon;

    private String color;

    private Integer sortOrder;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    @TableLogic
    private Integer deleted;
}
