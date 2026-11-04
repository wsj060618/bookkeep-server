package com.bookkeep.model.dto.resp;

import com.bookkeep.model.entity.Book;

import java.time.LocalDateTime;

/**
 * 回收站里的一本账本。
 * <p>{@code expireAt} 是后端算好的「彻底删除时刻」（deletedAt + 15 天）：
 * 前端直接显示「还剩 N 天」，不要自己拿系统时间去减——客户端时钟不可信。
 */
public record BookTrashResp(
        Long id,
        String name,
        LocalDateTime deletedAt,
        LocalDateTime expireAt
) {

    public static BookTrashResp from(Book book, int retentionDays) {
        LocalDateTime deletedAt = book.getDeletedAt();
        return new BookTrashResp(
                book.getId(),
                book.getName(),
                deletedAt,
                deletedAt == null ? null : deletedAt.plusDays(retentionDays)
        );
    }
}