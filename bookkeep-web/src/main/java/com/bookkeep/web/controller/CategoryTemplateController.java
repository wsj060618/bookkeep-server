package com.bookkeep.web.controller;

import com.bookkeep.common.result.Result;
import com.bookkeep.model.dto.TemplateDoc;
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

    /** 从零新建：`{name, items:[{name,type,icon,color}]}`，items 必须非空 */
    @PostMapping
    public Result<CategoryTemplateResp> create(@Valid @RequestBody CategoryTemplateReq req) {
        return Result.success(categoryTemplateService.create(req));
    }

    /** 把**当前账本**的分类快照成一个新模板（只带 name） */
    @PostMapping("/from-book")
    public Result<CategoryTemplateResp> createFromBook(@Valid @RequestBody CategoryTemplateReq req) {
        return Result.success(categoryTemplateService.createFromBook(req));
    }

    /** 改名；带 items 时**整体替换**条目 */
    @PutMapping("/{id}")
    public Result<CategoryTemplateResp> update(@PathVariable Long id,
                                               @Valid @RequestBody CategoryTemplateReq req) {
        return Result.success(categoryTemplateService.update(id, req));
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        categoryTemplateService.delete(id);
        return Result.success();
    }

    /**
     * 应用到当前账本：账本有流水时 400（换模板会断掉已有流水的分类归属）。
     * <p>界面已改成「新建账本时选模板」，此接口保留备用。
     */
    @PostMapping("/{id}/apply")
    public Result<Void> apply(@PathVariable Long id) {
        categoryTemplateService.apply(id);
        return Result.success();
    }

    /**
     * 导出模板：返回文档本身（含图标/颜色/排序），前端存成 {@code 模板名_分类模板.json}。
     * <p>不走文件流是刻意的：参数错误仍能返回正常的 200+JSON 信封。
     */
    @GetMapping("/{id}/export")
    public Result<TemplateDoc> export(@PathVariable Long id) {
        return Result.success(categoryTemplateService.exportDoc(id));
    }

    /** 导入模板：JSON 文档 → **新模板**（重名自动加「（导入）」后缀），不改任何账本 */
    @PostMapping("/import")
    public Result<CategoryTemplateResp> importDoc(@RequestBody TemplateDoc doc) {
        return Result.success(categoryTemplateService.importDoc(doc));
    }
}