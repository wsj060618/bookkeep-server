package com.bookkeep.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 公示链接：**一条链接可以带 1..N 本账本**（免登录只读，PLAN §5.9）。
 * <p>为什么从「每本一个 token」改成这张表：同学只想发一条链接，就能同时看到几本账本；
 * token 挂在链接上，账本只是链接的成员（{@link ShareLinkBook}）。
 * <p>每个账号最多一条有效链接：重新分享会删掉旧链接，泄漏时再分享一次即可作废。
 */
@Data
@TableName("share_link")
public class ShareLink {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    /** 32 位随机串（UUID 去横线），全局唯一 */
    private String token;

    /** 到期时间：创建时 now + 3 天，过期即 404 */
    private LocalDateTime expiresAt;

    private LocalDateTime createdAt;
}