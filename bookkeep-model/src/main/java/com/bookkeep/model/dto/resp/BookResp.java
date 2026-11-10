package com.bookkeep.model.dto.resp;

import com.bookkeep.model.entity.Book;

import java.time.LocalDate;
import java.util.Objects;

/** 账本响应；current 是算出来的（是否等于 user.current_book_id），archived = 只读 */
public record BookResp(
        Long id,
        String name,
        String type,
        Boolean current,
        Long categoryTemplateId,
        LocalDate semesterEnd,
        Boolean archived
) {

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