package com.bookkeep.web;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.bookkeep.service.constant.CategoryTemplate;
import com.bookkeep.model.entity.Category;
import com.bookkeep.service.mapper.CategoryMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@Transactional // 默认回滚
class CategoryMapperTest {

    @Autowired
    private CategoryMapper categoryMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("模板分类能整体落库，字段映射（sort_order/type）正确")
    void insertFromTemplate() {
        Long bookId = 1L;
        List<CategoryTemplate.Seed> seeds = CategoryTemplate.of(CategoryTemplate.BOOK_PERSONAL);
        assertEquals(12, seeds.size());

        // 图标/颜色存的是语义 key（前端映射），不是 emoji 或 hex
        for (CategoryTemplate.Seed seed : seeds) {
            assertTrue(seed.icon().matches("[a-z][a-z0-9_]*"), "icon 应为语义 key：" + seed.icon());
            assertTrue(seed.color().matches("[a-z][a-z0-9_]*"), "color 应为语义 key：" + seed.color());
        }

        for (CategoryTemplate.Seed seed : seeds) {
            Category category = new Category();
            category.setBookId(bookId);
            category.setName(seed.name());
            category.setType(seed.type());
            category.setIcon(seed.icon());
            category.setColor(seed.color());
            category.setSortOrder(seed.sortOrder());
            categoryMapper.insert(category);
            assertNotNull(category.getId());
        }

        CategoryTemplate.Seed first = seeds.get(0);
        Category found = categoryMapper.selectOne(
                Wrappers.<Category>query().eq("book_id", bookId).eq("name", first.name())
                        .eq("type", first.type()));
        assertNotNull(found);
        assertEquals(first.sortOrder(), found.getSortOrder());
        assertEquals(first.icon(), found.getIcon());
        assertEquals(first.color(), found.getColor());
        assertEquals(CategoryTemplate.TYPE_EXPENSE, found.getType());

        String raw = jdbcTemplate.queryForObject(
                "SELECT type FROM category WHERE id = ?", String.class, found.getId());
        assertEquals("expense", raw);
    }

    @Test
    @DisplayName("按 type 筛选，income / expense 各自条数正确")
    void filterByType() {
        Long bookId = 1L;
        for (CategoryTemplate.Seed seed : CategoryTemplate.of(CategoryTemplate.BOOK_MERCHANT)) {
            Category category = new Category();
            category.setBookId(bookId);
            category.setName(seed.name());
            category.setType(seed.type());
            category.setIcon(seed.icon());
            category.setColor(seed.color());
            category.setSortOrder(seed.sortOrder());
            categoryMapper.insert(category);
        }

        long expenses = categoryMapper.selectCount(
                Wrappers.<Category>query().eq("book_id", bookId).eq("type", CategoryTemplate.TYPE_EXPENSE));
        long incomes = categoryMapper.selectCount(
                Wrappers.<Category>query().eq("book_id", bookId).eq("type", CategoryTemplate.TYPE_INCOME));

        assertEquals(9, expenses);
        assertEquals(3, incomes);
    }

    @Test
    @DisplayName("逻辑删除后查不到")
    void logicDelete() {
        Category category = new Category();
        category.setBookId(1L);
        category.setName("临时分类_" + System.currentTimeMillis());
        category.setType(CategoryTemplate.TYPE_EXPENSE);
        category.setSortOrder(99);
        categoryMapper.insert(category);
        assertNotNull(category.getId());

        categoryMapper.deleteById(category.getId());
        assertNull(categoryMapper.selectById(category.getId()));
    }
}
