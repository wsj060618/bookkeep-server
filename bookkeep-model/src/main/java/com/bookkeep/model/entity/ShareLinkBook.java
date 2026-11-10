package com.bookkeep.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 公示链接包含的账本（多对多，物理删除）。
 * <p>账本进回收站时不动这里（恢复后链接照旧能看）；账本**彻底删除**时要把成员关系一起清掉，
 * 否则链接里会留下一个永远打不开的 id。
 */
@Data
@TableName("share_link_book")
public class ShareLinkBook {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long shareLinkId;

    private Long bookId;

    /** 展示顺序（按用户勾选 / 创建顺序） */
    private Integer sortOrder;
}