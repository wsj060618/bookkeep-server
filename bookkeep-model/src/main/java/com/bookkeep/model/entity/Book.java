package com.bookkeep.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@TableName("book")
public class Book {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    private String name;

    /**
     * 账本类型。当前项目只有班级账本一种，固定填 {@code class}
     * （常量见 {@code CategoryPresets.BOOK_CLASS}）；列保留是为了将来扩展其它账本类型时不用改表。
     */
    private String type;

    /**
     * 当前分类来自哪套自定义模板；**NULL = 内置「班级班费」模板**（id=0，不入库）。
     * <p>用户手工增删改分类后这里不清空：语义是「基于哪套模板」，不是「与模板完全一致」。
     */
    private Long categoryTemplateId;

    /** 学期截止日期（可空）：只用于展示与提示，不参与任何统计；「一个学期 = 一本账本」 */
    private LocalDate semesterEnd;

    /** 学期归档：1 = 只读（仍可改名 / 改截止日期 / 取消归档 / 导出 / 公示） */
    private Integer archived;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    @TableLogic
    private Integer deleted;

    /**
     * 进入回收站的时间（deleted=1 时才有值）。满 15 天由定时任务连同分类、流水彻底删除。
     * <p>为什么不用 {@code updated_at}：它是 ON UPDATE 自动列的，改名也会动，算不出「删了多久」。
     */
    private LocalDateTime deletedAt;
}
