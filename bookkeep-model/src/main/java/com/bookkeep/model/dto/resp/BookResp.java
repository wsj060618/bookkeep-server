package com.bookkeep.model.dto.resp;

import com.bookkeep.model.entity.Book;

import java.util.Objects;

/**
 * 账本响应体（GET /api/books、登录返回的 books 数组共用）
 * <p>{@code current} 不是库里的字段，而是「这本是不是 user.current_book_id」的推导结果，
 * 前端靠它高亮当前账本，不用自己拿 currentBookId 去比对。
 */
public record BookResp(
        Long id,
        String name,
        String type,
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
                Objects.equals(book.getId(), currentBookId)
        );
    }
}
