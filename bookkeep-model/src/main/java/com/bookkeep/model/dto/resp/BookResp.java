package com.bookkeep.model.dto.resp;

import com.bookkeep.model.entity.Book;

import java.util.Objects;

/**
 * 账本响应体（登录返回的 books 数组）
 * <p>{@code current} 不是库里的字段，而是「这本是不是 user.current_book_id」的推导结果，
 * 前端靠它高亮当前账本，不用自己拿 currentBookId 去比对。
 * <p>{@code shareToken} 是公示页链接 {@code /report/{token}} 的原料：前端要靠它拼分享链接，
 * 否则「公示页」这个功能在界面上无从入口（PLAN §5.9）。
 */
public record BookResp(
        Long id,
        String name,
        String type,
        String shareToken,
        Boolean current
) {

    /**
     * 实体 → 响应体
     *
     * @param book          账本实体
     * @param currentBookId 用户当前的账本 id，可为 null（用户一本账本都没有）
     */
    public static BookResp from(Book book, Long currentBookId) {
        return new BookResp(
                book.getId(),
                book.getName(),
                book.getType(),
                book.getShareToken(),
                Objects.equals(book.getId(), currentBookId)
        );
    }
}