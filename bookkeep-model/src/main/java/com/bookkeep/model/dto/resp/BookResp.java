package com.bookkeep.model.dto.resp;

import com.bookkeep.model.entity.Book;

import java.time.LocalDateTime;
import java.util.Objects;

/**
 * 账本响应体（登录返回的 books 数组）。
 * <p>{@code current} 不是库里的字段，而是「这本是不是 user.current_book_id」的推导结果。
 * 前端靠它高亮当前账本，不用自己拿 currentBookId 去比对。
 * <p>{@code shareToken} / {@code shareTokenExpiresAt} 是公示页链接 {@code /report/{token}} 的原料：
 * 从未分享过就是 null；链接过期后 token 仍在库里，但过期时间已过，后端会按 404 处理，前端应引导重新分享。
 */
public record BookResp(
        Long id,
        String name,
        String type,
        String shareToken,
        LocalDateTime shareTokenExpiresAt,
        Boolean current,
        Long categoryTemplateId
) {

    /**
     * 实体 → 响应体
     *
     * @param book          账本实体
     * @param currentBookId 用户当前的账本 id，可空（用户一本账本都没有时）
     */
    public static BookResp from(Book book, Long currentBookId) {
        return new BookResp(
                book.getId(),
                book.getName(),
                book.getType(),
                book.getShareToken(),
                book.getShareTokenExpiresAt(),
                Objects.equals(book.getId(), currentBookId),
                book.getCategoryTemplateId()
        );
    }
}