package com.bookkeep.web;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.bookkeep.model.entity.Book;
import com.bookkeep.service.constant.CategoryTemplate;
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

    /**
     * 用一个不会真实存在的 user_id：
     * 测试事务会回滚，但「按条件查询」如果用一个常见的 id（比如 1），
     * 开发库里已有的真实账本会让断言命中多余的行——这类失败很难一眼看出原因。
     */
    private static final Long TEST_USER_ID = 999_999L;

    @Autowired
    private BookMapper bookMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("type 以小写字面量落库，回查一致")
    void typeGoesToDbAndBack() {
        Book book = new Book();
        book.setUserId(TEST_USER_ID);
        book.setName("测试账本_" + System.currentTimeMillis());
        book.setType(CategoryTemplate.BOOK_CLASS);
        bookMapper.insert(book);
        assertNotNull(book.getId());

        // 直接读原始列，确认库里是小写字面量
        String raw = jdbcTemplate.queryForObject(
                "SELECT type FROM book WHERE id = ?", String.class, book.getId());
        assertEquals("class", raw);

        Book found = bookMapper.selectById(book.getId());
        assertNotNull(found);
        assertEquals("class", found.getType());
        assertEquals(book.getName(), found.getName());
    }

    @Test
    @DisplayName("按 type + user_id 筛选只命中本账号的账本")
    void filterByTypeAndUser() {
        Book first = newBook(TEST_USER_ID, "第一本");
        Book second = newBook(TEST_USER_ID, "第二本");
        newBook(TEST_USER_ID + 1, "别人的账本");

        List<Long> ids = bookMapper.selectList(Wrappers.<Book>query()
                        .eq("type", CategoryTemplate.BOOK_CLASS)
                        .eq("user_id", TEST_USER_ID)
                        .orderByAsc("id"))
                .stream().map(Book::getId).toList();

        assertEquals(List.of(first.getId(), second.getId()), ids);
    }

    @Test
    @DisplayName("逻辑删除后查不到")
    void logicDelete() {
        Book book = newBook(TEST_USER_ID, "待删账本");

        bookMapper.deleteById(book.getId());
        assertNull(bookMapper.selectById(book.getId()));
    }

    private Book newBook(Long userId, String name) {
        Book book = new Book();
        book.setUserId(userId);
        book.setName(name + "_" + System.nanoTime());
        book.setType(CategoryTemplate.BOOK_CLASS);
        bookMapper.insert(book);
        return book;
    }
}