package com.bookkeep.service;

import com.bookkeep.model.dto.req.CategoryTemplateReq;
import com.bookkeep.model.dto.resp.CategoryTemplateResp;

import java.util.List;

/**
 * 分类模板：**跟账号走**的模板库，一套模板可应用到该用户的任意账本。
 * <p>内置「班级班费」模板用虚拟 id {@code 0} 表示，不入库，内容取自
 * {@code CategoryPresets.classCategories()}——和注册时预置的分类永远同源。
 */
public interface CategoryTemplateService {

    /** 模板库列表：内置模板排第一，之后是用户自建的（按创建顺序）。条目一并返回。 */
    List<CategoryTemplateResp> list();

    /** 把**当前账本**的分类快照成一个新模板（当前账本没有分类时 400）。 */
    CategoryTemplateResp create(CategoryTemplateReq req);

    /** 改名。内置模板不能改（403 语义），模板名在同一用户内唯一。 */
    CategoryTemplateResp rename(Long id, CategoryTemplateReq req);

    /** 物理删除。引用它的账本（含回收站）自动指回内置模板。 */
    void delete(Long id);

    /**
     * 把模板应用到**当前账本**：先逻辑删除现有分类，再按模板条目逐条新建。
     * <p>账本里已有流水时一律 400——换模板会把分类整套换掉，历史流水的分类归属就断了。
     */
    void apply(Long id);
}
