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

    @Test
    void bookkeepTablesShouldExist() {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = 'bookkeep'",
                Integer.class);
        assertNotNull(count);
        assertEquals(5, count, "bookkeep 库应有 5 张表");
    }
}
