package com.bookkeep.model.dto.resp;

import com.bookkeep.model.entity.Book;

import java.time.LocalDate;
import java.util.Objects;

/**
 * 账本响应体（登录返回的 books 数组）。
 * <p>{@code current} 不是库存字段，而是「这本是不是 user.current_book_id」算出来的，
 * 前端可以直接用，也可以自己拿 currentBookId 去比对。
 * <p>公示链接不在这里：链接是独立的实体（一条链接可以带多本账本），要分享调
 * `POST /api/books/share`，返回的是链接与它包含的账本。
 * <p>{@code semesterEnd} 是学期截止日期（可空，只展示不参与统计）；
 * {@code archived} 为 true 时账本只读：不能再记流水、不能增删改分类，
 * 但仍可改名 / 改截止日期 / 取消归档 / 导出 / 公示。
 */
public record BookResp(
        Long id,
        String name,
        String type,
        Boolean current,
        Long categoryTemplateId,
        LocalDate semesterEnd,
        Boolean archived
) {

    /**
     * 实体 → 响应
     *
     * @param book          账本实体
     * @param currentBookId 用户当前账本 id，可空（用户一本账本都没有时）
     */
    public static BookResp from(Book book, Long currentBookId) {
        return new BookResp(
                book.getId(),
                book.getName(),
                book.getType(),
                Objects.equals(book.getId(), currentBookId),
                book.getCategoryTemplateId(),
                book.getSemesterEnd(),
                book.getArchived() != null && book.getArchived() == 1
        );
    }
}