package com.bookkeep.web;

import com.bookkeep.service.MaintenanceService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 过期数据清理（{@link MaintenanceService#cleanupExpired()}）。
 * <p>只清「过了有效期就再也不可能被读到」的行：找回密码 token、公示链接及其成员行；
 * 没过期的必须原样留着，否则用户会莫名其妙失去正在使用的链接。
 */
@SpringBootTest
@Transactional
class ExpiredDataCleanupTest {

    /** 与真账号隔开，避免和手工测试数据撞车（测试本身回滚，但查询要看清） */
    private static final long FAKE_USER_ID = 999_998L;
    private static final long FAKE_BOOK_ID = 999_998L;

    private static final String EXPIRED_TOKEN = "a".repeat(64);
    private static final String VALID_TOKEN = "b".repeat(64);
    private static final String EXPIRED_LINK = "e".repeat(32);
    private static final String VALID_LINK = "f".repeat(32);

    @Autowired
    private MaintenanceService maintenanceService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("清掉过期的 token 与公示链接，没过期的一行不动")
    void deletesOnlyExpiredRows() {
        jdbcTemplate.update("INSERT INTO password_reset_token (user_id, token_hash, expires_at) "
                + "VALUES (?, ?, NOW() - INTERVAL 1 HOUR)", FAKE_USER_ID, EXPIRED_TOKEN);
        jdbcTemplate.update("INSERT INTO password_reset_token (user_id, token_hash, expires_at) "
                + "VALUES (?, ?, NOW() + INTERVAL 10 MINUTE)", FAKE_USER_ID, VALID_TOKEN);

        jdbcTemplate.update("INSERT INTO share_link (user_id, token, expires_at) "
                + "VALUES (?, ?, NOW() - INTERVAL 1 DAY)", FAKE_USER_ID, EXPIRED_LINK);
        jdbcTemplate.update("INSERT INTO share_link (user_id, token, expires_at) "
                + "VALUES (?, ?, NOW() + INTERVAL 2 DAY)", FAKE_USER_ID, VALID_LINK);
        jdbcTemplate.update("INSERT INTO share_link_book (share_link_id, book_id) "
                + "SELECT id, ? FROM share_link WHERE token IN (?, ?)", FAKE_BOOK_ID, EXPIRED_LINK, VALID_LINK);

        int removed = maintenanceService.cleanupExpired();

        // 本用例自己造的 1 token + 1 链接 + 1 成员行；库里可能还有别的过期行，所以用 >=
        assertTrue(removed >= 3, "至少应清掉本用例造的 3 行，实际 " + removed);
        assertEquals(0, count("SELECT COUNT(*) FROM password_reset_token WHERE token_hash = ?", EXPIRED_TOKEN));
        assertEquals(1, count("SELECT COUNT(*) FROM password_reset_token WHERE token_hash = ?", VALID_TOKEN));
        assertEquals(0, count("SELECT COUNT(*) FROM share_link WHERE token = ?", EXPIRED_LINK));
        assertEquals(1, count("SELECT COUNT(*) FROM share_link WHERE token = ?", VALID_LINK));
        // 成员行先删：过期链接的那条不能留下变成孤儿
        assertEquals(0, count("SELECT COUNT(*) FROM share_link_book WHERE book_id = ? AND share_link_id NOT IN "
                + "(SELECT id FROM share_link WHERE token = ?)", FAKE_BOOK_ID, VALID_LINK));
        assertEquals(1, count("SELECT COUNT(*) FROM share_link_book WHERE book_id = ?", FAKE_BOOK_ID));
    }

    @Test
    @DisplayName("没过期的 token 不会被误删")
    void validRowsSurvive() {
        jdbcTemplate.update("INSERT INTO password_reset_token (user_id, token_hash, expires_at) "
                + "VALUES (?, ?, NOW() + INTERVAL 10 MINUTE)", FAKE_USER_ID, VALID_TOKEN);

        maintenanceService.cleanupExpired();

        assertEquals(1, count("SELECT COUNT(*) FROM password_reset_token WHERE token_hash = ?", VALID_TOKEN));
    }

    private int count(String sql, Object... args) {
        Integer value = jdbcTemplate.queryForObject(sql, Integer.class, args);
        return value == null ? 0 : value;
    }
}