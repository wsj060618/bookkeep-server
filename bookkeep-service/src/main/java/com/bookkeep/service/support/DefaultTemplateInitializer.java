package com.bookkeep.service.support;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.bookkeep.model.entity.Book;
import com.bookkeep.model.entity.CategoryTemplate;
import com.bookkeep.model.entity.CategoryTemplateItem;
import com.bookkeep.model.entity.User;
import com.bookkeep.service.constant.CategoryPresets;
import com.bookkeep.service.mapper.BookMapper;
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
 * 给**历史账号**补一套默认分类模板。
 *
 * <p>为什么需要：早期「内置模板」只是 `CategoryPresets` 的常量投影（id=0，库里没有行），
 * 所以改不了、也不能在列表里当普通模板编辑。现在注册时会直接落一套真实模板
 * （见 AuthServiceImpl#register），历史账号就得补一次，否则它们仍然只有那个只读投影。
 *
 * <p>三条安全约束：
 * <ol>
 *   <li>**只在账号一套模板都没有时**才补——用户自己删光了模板也不该被强行塞回来？
 *       这里选择补（否则该账号再也建不了模板，且页面只剩只读投影），代价是可以接受的一次性行为</li>
 *   <li>补完把 `book.category_template_id IS NULL` 的老账本指向它（旧语义 NULL = 内置），
 *       否则前端「当前账本用的是哪套模板」会显示不出来</li>
 *   <li>不改任何分类数据：账本里的 category 行保持原样（它们本来就是这套 seeds 的拷贝）</li>
 * </ol>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DefaultTemplateInitializer implements ApplicationRunner {

    private final UserMapper userMapper;
    private final BookMapper bookMapper;
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