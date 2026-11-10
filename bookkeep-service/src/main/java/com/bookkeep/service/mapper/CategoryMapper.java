package com.bookkeep.service.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.bookkeep.model.entity.Category;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

public interface CategoryMapper extends BaseMapper<Category> {

    /**
     * 删模板前清掉分类上的来源标记。
     * <p>账本的分类是拷贝，模板删了它们要留着；但 `template_item_id` 会指向不存在的条目，
     * 留着只会在下次同步时被误当成「模板里已经没有的条目」去删。
     */
    @Update("UPDATE category SET template_item_id = NULL "
            + "WHERE deleted = 0 AND template_item_id IN "
            + "(SELECT id FROM category_template_item WHERE template_id = #{templateId})")
    int clearTemplateItemLinks(@Param("templateId") Long templateId);

    /**
     * 把「已指向这套模板的账本」里的分类按「同方向 + 同名」挂回模板条目。
     * <p>用于历史账号补建默认模板（{@code DefaultTemplateInitializer}），口径与
     * `migration_template_item_link.sql` 第 3 步一致；已经是模板条目的行不动。
     */
    @Update("UPDATE category c "
            + "JOIN category_template_item i "
            + "  ON i.template_id = #{templateId} AND i.type = c.type AND i.name = c.name "
            + "SET c.template_item_id = i.id "
            + "WHERE c.deleted = 0 AND c.template_item_id IS NULL "
            + "  AND c.book_id IN (SELECT b.id FROM book b "
            + "                    WHERE b.category_template_id = #{templateId} AND b.deleted = 0)")
    int linkToTemplateItems(@Param("templateId") Long templateId);
}