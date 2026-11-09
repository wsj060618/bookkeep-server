package com.bookkeep.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 模板条目：字段与 {@link Category} 一一对应，应用模板时按 {@code sortOrder} 逐条复制成分类。
 * <p>改模板 = 整体删掉重插（物理删除），所以没有 {@code deleted} 列。
 */
@Data
@TableName("category_template_item")
public class CategoryTemplateItem {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long templateId;

    private String name;

    /** income / expense，取值见 {@code CategoryTemplate} */
    private String type;

    private String icon;

    private String color;

    private Integer sortOrder;
}
