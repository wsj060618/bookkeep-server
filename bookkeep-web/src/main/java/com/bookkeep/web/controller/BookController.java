package com.bookkeep.web.controller;

import com.bookkeep.common.result.Result;
import com.bookkeep.model.dto.req.BookUpdateReq;
import com.bookkeep.model.dto.resp.BookResp;
import com.bookkeep.service.BookService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 账本接口（PLAN §5.2：只提供重命名，不做新建 / 删除 / 切换）。
 * <p>账本列表不在这里——它随 {@code /api/auth/login} 的 {@code books} 返回。
 */
@RestController
@RequestMapping("/api/books")
@RequiredArgsConstructor
public class BookController {

    private final BookService bookService;

    /** 重命名自己的账本：{@code PUT /api/books/1} body {@code {"name":"计科2301班费"}} */
    @PutMapping("/{id}")
    public Result<BookResp> rename(@PathVariable Long id, @Valid @RequestBody BookUpdateReq req) {
        return Result.success(bookService.rename(id, req));
    }
}