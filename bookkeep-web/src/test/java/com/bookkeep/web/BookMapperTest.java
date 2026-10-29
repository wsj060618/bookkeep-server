package com.bookkeep.web;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.bookkeep.service.constant.CategoryTemplate;
import com.bookkeep.model.entity.Book;
import com.bookkeep.service.mapper.BookMapper;
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

@SpringBootTest
@Transactional
class BookMapperTest {

    @Autowired
    private BookMapper bookMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("type 以小写字面量落库，回查一致")
    void typeGoesToDbAndBack() {
        Book book = new Book();
        book.setUserId(1L);
        book.setName("测试账本_" + System.currentTimeMillis());
        book.setType(CategoryTemplate.BOOK_MERCHANT);
        bookMapper.insert(book);
        assertNotNull(book.getId());

        // 直接读原始列，确认库里是小写字面量
        String raw = jdbcTemplate.queryForObject(
                "SELECT type FROM book WHERE id = ?", String.class, book.getId());
        assertEquals("merchant", raw);

        Book found = bookMapper.selectById(book.getId());
        assertNotNull(found);
        assertEquals("merchant", found.getType());
        assertEquals(book.getName(), found.getName());
    }

    @Test
    @DisplayName("按 type 条件筛选只命中对应账本")
    void filterByType() {
        Book personal = new Book();
        personal.setUserId(1L);
        personal.setName("个人账本_" + System.currentTimeMillis());
        personal.setType(CategoryTemplate.BOOK_PERSONAL);
        bookMapper.insert(personal);

        Book merchant = new Book();
        merchant.setUserId(1L);
        merchant.setName("商家账本_" + System.currentTimeMillis());
        merchant.setType(CategoryTemplate.BOOK_MERCHANT);
        bookMapper.insert(merchant);

        List<Book> personals = bookMapper.selectList(
                Wrappers.<Book>query().eq("type", CategoryTemplate.BOOK_PERSONAL).eq("user_id", 1L));
        assertEquals(List.of(personal.getId()),
                personals.stream().map(Book::getId).toList());
    }

    @Test
    @DisplayName("逻辑删除后查不到")
    void logicDelete() {
        Book book = new Book();
        book.setUserId(1L);
        book.setName("待删账本_" + System.currentTimeMillis());
        book.setType(CategoryTemplate.BOOK_PERSONAL);
        bookMapper.insert(book);

        bookMapper.deleteById(book.getId());
        assertNull(bookMapper.selectById(book.getId()));
    }
}
