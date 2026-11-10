package com.bookkeep.web;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.sql.Connection;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class DataSourceConnectionTest {

    @Autowired
    private DataSource dataSource;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void connectionShouldBeValid() throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            assertTrue(conn.isValid(2), "数据库连接不可用");
            var meta = conn.getMetaData();
            System.out.printf("DB: %s %s%nURL: %s%nUSER: %s%n",
                    meta.getDatabaseProductName(),
                    meta.getDatabaseProductVersion(),
                    meta.getURL(),
                    meta.getUserName());
        }
    }

    @Test
    void selectOneShouldWork() {
        Integer one = jdbcTemplate.queryForObject("SELECT 1", Integer.class);
        assertEquals(1, one);
    }

    /** 断言必需的表都在，而不是数具体几张：加表（如分类模板）不该让这个测试变红。 */
    @Test
    void bookkeepTablesShouldExist() {
        var required = java.util.List.of(
                "user", "book", "category", "transaction",
                "password_reset_token", "category_template", "category_template_item",
                "share_link", "share_link_book", "transaction_receipt");
        for (String table : required) {
            Integer count = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM information_schema.tables "
                            + "WHERE table_schema = 'bookkeep' AND table_name = ?",
                    Integer.class, table);
            assertEquals(1, count, "bookkeep 库缺少表：" + table);
        }
    }
}
