package com.bookkeep.web.controller;

import com.bookkeep.common.result.Result;
import com.bookkeep.model.dto.req.BatchIdsReq;
import com.bookkeep.model.dto.req.CategoryReq;
import com.bookkeep.model.dto.resp.CategoryResp;
import com.bookkeep.service.CategoryService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 分类接口（按当前账本）。
 * <p><strong>不接受任何 bookId / userId 参数</strong>：账本由 {@code SecurityUtil} + {@code CurrentBookService}
 * 从 token 推导，前端传了就说明设计漏了（PLAN §2.3）。
 */
@RestController
@RequestMapping("/api/categories")
@RequiredArgsConstructor
public class CategoryController {

    private final CategoryService categoryService;

    @GetMapping
    public Result<List<CategoryResp>> list(@RequestParam(required = false) String type) {
        return Result.success(categoryService.list(type));
    }

    @PostMapping
    public Result<CategoryResp> create(@Valid @RequestBody CategoryReq req) {
        return Result.success(categoryService.create(req));
    }

    @PutMapping("/{id}")
    public Result<CategoryResp> update(@PathVariable Long id, @Valid @RequestBody CategoryReq req) {
        return Result.success(categoryService.update(id, req));
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        categoryService.delete(id);
        return Result.success();
    }

    /** 批量删除分类；有一条被流水引用就整批拒绝（不做「删一半」） */
    @PostMapping("/batch-delete")
    public Result<Integer> deleteBatch(@Valid @RequestBody BatchIdsReq req) {
        return Result.success(categoryService.deleteBatch(req.ids()));
    }
}