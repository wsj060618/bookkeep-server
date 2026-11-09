package com.bookkeep.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 自定义分类模板，**跟账号走**（不按账本隔离）：一套模板可用于该用户的任意账本。
 * <p>内置的「班级班费」模板不入库，id 固定为 {@code 0}，内容直接取
 * {@code CategoryPresets.classCategories()}——它与注册时预置的分类同源。
 * <p>本表物理删除（无 {@code deleted} 列）：模板条目是整体替换的，逻辑删除没有意义。
 */
@Data
@TableName("category_template")
public class CategoryTemplate {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    private String name;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
