package com.bookkeep.service;

import com.bookkeep.model.dto.req.CategoryTemplateReq;
import com.bookkeep.model.dto.resp.CategoryTemplateResp;

import java.util.List;

/**
 * 分类模板：账号级。内置「班级班费」用 id 0 表示，内容取自 CategoryPresets。
 */
public interface CategoryTemplateService {

    List<CategoryTemplateResp> list();

    CategoryTemplateResp create(CategoryTemplateReq req);

    /** 把当前账本的分类快照成新模板 */
    CategoryTemplateResp createFromBook(CategoryTemplateReq req);

    /** items 非空时按 id 合并条目；内置模板不能改 */
    CategoryTemplateResp update(Long id, CategoryTemplateReq req);

    void delete(Long id);

    /** 应用到当前账本；账本有流水时 400。界面已不露出，保留备用 */
    void apply(Long id);

    /** 取模板条目（新建账本时预置分类用）；null / 0 = 内置 */
    List<CategoryTemplateResp.Item> itemsOf(Long templateId);
}