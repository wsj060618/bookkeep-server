package com.bookkeep.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

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
     * （常量见 {@code CategoryTemplate.BOOK_CLASS}）；列保留是为了将来扩展其它账本类型时不用改表。
     */
    private String type;

    /**
     * 公示页随机 token（免登录只读，见 PLAN §5.9）。
     * 注册时生成 32 位 UUID 去横线；同一本书唯一，用于 {@code GET /api/public/report/{token}}。
     */
    private String shareToken;

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
