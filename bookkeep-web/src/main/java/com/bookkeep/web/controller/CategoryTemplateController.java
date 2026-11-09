package com.bookkeep.web.controller;

import com.bookkeep.common.result.Result;
import com.bookkeep.model.dto.req.CategoryTemplateReq;
import com.bookkeep.model.dto.resp.CategoryTemplateResp;
import com.bookkeep.service.CategoryTemplateService;
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
 * 分类模板接口。模板**跟账号走**（不按账本隔离），应用时才落到当前账本。
 * <p>内置模板用虚拟 id {@code 0} 表示，不能改名/删除，只能应用。
 * <p>与其他业务接口一样：**不接受任何 userId / bookId 参数**，都从 token 推导。
 */
@RestController
@RequestMapping("/api/category-templates")
@RequiredArgsConstructor
public class CategoryTemplateController {

    private final CategoryTemplateService categoryTemplateService;

    @GetMapping
    public Result<List<CategoryTemplateResp>> list() {
        return Result.success(categoryTemplateService.list());
    }

    /** 从**当前账本**的分类快照出一个新模板（不传条目，避免模板与账本对不上） */
    @PostMapping
    public Result<CategoryTemplateResp> create(@Valid @RequestBody CategoryTemplateReq req) {
        return Result.success(categoryTemplateService.create(req));
    }

    @PutMapping("/{id}")
    public Result<CategoryTemplateResp> rename(@PathVariable Long id,
                                               @Valid @RequestBody CategoryTemplateReq req) {
        return Result.success(categoryTemplateService.rename(id, req));
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        categoryTemplateService.delete(id);
        return Result.success();
    }

    /** 应用到当前账本：账本有流水时 400（换模板会断掉已有流水的分类归属） */
    @PostMapping("/{id}/apply")
    public Result<Void> apply(@PathVariable Long id) {
        categoryTemplateService.apply(id);
        return Result.success();
    }
}
