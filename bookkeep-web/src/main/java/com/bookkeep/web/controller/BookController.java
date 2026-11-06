package com.bookkeep.web.controller;

import com.bookkeep.common.result.Result;
import com.bookkeep.model.dto.req.BookUpdateReq;
import com.bookkeep.model.dto.resp.BookResp;
import com.bookkeep.model.dto.resp.BookTrashResp;
import com.bookkeep.model.dto.resp.ShareLinkResp;
import com.bookkeep.service.BookService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 账本接口（PLAN §5.2）：增删改查 + 回收站。
 * <p>删除是「进回收站」：15 天内可恢复，到期由定时任务连同分类、流水彻底删除。
 * <p>所有接口都不接收 userId；当前账本永远是 {@code user.current_book_id}，切换只能走 {@code /current}。
 */
@RestController
@RequestMapping("/api/books")
@RequiredArgsConstructor
public class BookController {

    private final BookService bookService;

    /** 我的账本（不含回收站），按创建顺序 */
    @GetMapping
    public Result<List<BookResp>> list() {
        return Result.success(bookService.list());
    }

    /** 新建账本：预置 13 条分类，并自动切为当前 */
    @PostMapping
    public Result<BookResp> create(@Valid @RequestBody BookUpdateReq req) {
        return Result.success(bookService.create(req));
    }

    /** 分享：每次点都换新 token（3 天有效），旧链接立即失效 */
    @PostMapping("/{id}/share")
    public Result<ShareLinkResp> share(@PathVariable Long id) {
        return Result.success(bookService.share(id));
    }

    /** 重命名：只改 name，shareToken 不动（改名不该让已发出去的公示链接失效） */
    @PutMapping("/{id}")
    public Result<BookResp> rename(@PathVariable Long id, @Valid @RequestBody BookUpdateReq req) {
        return Result.success(bookService.rename(id, req));
    }

    /** 切换当前账本，返回切换后的完整列表（前端直接替换本地 books） */
    @PutMapping("/{id}/current")
    public Result<List<BookResp>> switchCurrent(@PathVariable Long id) {
        return Result.success(bookService.switchCurrent(id));
    }

    /** 删除 → 回收站；至少保留一本 */
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        bookService.delete(id);
        return Result.success();
    }

    /** 回收站：带「彻底删除时刻」 */
    @GetMapping("/trash")
    public Result<List<BookTrashResp>> trash() {
        return Result.success(bookService.trash());
    }

    /** 从回收站恢复（不自动切成当前账本） */
    @PutMapping("/{id}/restore")
    public Result<BookResp> restore(@PathVariable Long id) {
        return Result.success(bookService.restore(id));
    }

    /** 立即彻底删除：只能删回收站里的账本，连同分类与流水，不可恢复 */
    @DeleteMapping("/{id}/permanent")
    public Result<Void> purge(@PathVariable Long id) {
        bookService.purge(id);
        return Result.success();
    }
}