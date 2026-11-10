package com.bookkeep.service.support;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.bookkeep.model.entity.Book;
import com.bookkeep.model.entity.CategoryTemplate;
import com.bookkeep.model.entity.CategoryTemplateItem;
import com.bookkeep.model.entity.User;
import com.bookkeep.service.constant.CategoryPresets;
import com.bookkeep.service.mapper.BookMapper;
import com.bookkeep.service.mapper.CategoryMapper;
import com.bookkeep.service.mapper.CategoryTemplateItemMapper;
import com.bookkeep.service.mapper.CategoryTemplateMapper;
import com.bookkeep.service.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 给历史账号补一套默认模板（注册时就有真实模板，这个只服务于老账号）。
 * <p>仅当账号一套模板都没有时才补，补完把 category_template_id 为 NULL 的老账本指过去，
 * 并把账本里同名的分类挂回条目。不改任何分类数据。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DefaultTemplateInitializer implements ApplicationRunner {

    private final UserMapper userMapper;
    private final BookMapper bookMapper;
    private final CategoryMapper categoryMapper;
    private final CategoryTemplateMapper templateMapper;
    private final CategoryTemplateItemMapper templateItemMapper;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void run(ApplicationArguments args) {
        List<User> users = userMapper.selectList(Wrappers.<User>query().select("id"));
        int created = 0;
        for (User user : users) {
            long existing = templateMapper.selectCount(Wrappers.<CategoryTemplate>query()
                    .eq("user_id", user.getId()));
            if (existing > 0) {
                continue;
            }
            Long templateId = createDefaultTemplate(user.getId());
            bookMapper.update(null, Wrappers.<Book>update()
                    .eq("user_id", user.getId())
                    .isNull("category_template_id")
                    .set("category_template_id", templateId));
            // 账本里原本就有的分类（当年按同一套 seeds 建的）挂回上面的条目，
            // 否则之后保存模板时认不出「这条分类是模板里的哪一条」
            categoryMapper.linkToTemplateItems(templateId);
            created++;
        }
        if (created > 0) {
            log.info("为 {} 个历史账号补建了默认分类模板（{}）", created, CategoryPresets.BUILTIN_NAME);
        }
    }

    private Long createDefaultTemplate(Long userId) {
        CategoryTemplate template = new CategoryTemplate();
        template.setUserId(userId);
        template.setName(CategoryPresets.BUILTIN_NAME);
        templateMapper.insert(template);
        for (CategoryPresets.Seed seed : CategoryPresets.classCategories()) {
            CategoryTemplateItem item = new CategoryTemplateItem();
            item.setTemplateId(template.getId());
            item.setName(seed.name());
            item.setType(seed.type());
            item.setIcon(seed.icon());
            item.setColor(seed.color());
            item.setSortOrder(seed.sortOrder());
            templateItemMapper.insert(item);
        }
        return template.getId();
    }
}