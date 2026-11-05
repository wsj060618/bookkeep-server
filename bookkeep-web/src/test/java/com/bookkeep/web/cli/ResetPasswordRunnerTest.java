package com.bookkeep.web.cli;

import com.bookkeep.model.entity.User;
import com.bookkeep.service.mapper.UserMapper;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 不启 Spring 上下文：只钉「校验规则 + BCrypt 落库」，System.exit 留在 run() 里不可测也不该测。
 */
class ResetPasswordRunnerTest {

    private final UserMapper userMapper = mock(UserMapper.class);
    // 强度降到 4 只是跑得快；matches() 与 bean 的默认强度无关
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(4);
    private final ResetPasswordRunner runner = new ResetPasswordRunner(userMapper, encoder);

    @Test
    void replacesPasswordWithBcryptHash() {
        User user = new User();
        user.setId(1L);
        user.setUsername("admin");
        when(userMapper.selectOne(any())).thenReturn(user);

        assertNull(runner.reset("admin", "newpass1"));

        assertTrue(encoder.matches("newpass1", user.getPassword()));
        verify(userMapper).updateById(user);
    }

    @Test
    void unknownUserIsRejectedWithoutUpdate() {
        when(userMapper.selectOne(any())).thenReturn(null);

        assertNotNull(runner.reset("ghost", "newpass1"));
        verify(userMapper, never()).updateById(any(User.class));
    }

    @Test
    void usernameAndPasswordFollowRegisterRules() {
        assertEquals("用户名需 4-20 位字母、数字或下划线", runner.reset("ab", "newpass1"));
        assertEquals("用户名需 4-20 位字母、数字或下划线", runner.reset("bad name", "newpass1"));
        assertEquals("密码长度需 6-32 位", runner.reset("admin", "12345"));
        assertEquals("密码长度需 6-32 位", runner.reset("admin", null));
        verify(userMapper, never()).updateById(any(User.class));
    }
}