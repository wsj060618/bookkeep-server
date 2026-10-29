package com.bookkeep.web;

import com.bookkeep.model.entity.User;
import com.bookkeep.service.mapper.UserMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

@SpringBootTest
@Transactional
class UserMapperTest {

    @Autowired
    private UserMapper userMapper;

    @Test
    @DisplayName("插入后能查到，逻辑删除后查不到")
    void insertSelectAndLogicDelete() {
        User user = new User();
        user.setUsername("test_user_" + System.currentTimeMillis());
        user.setPassword("irrelevant");
        userMapper.insert(user);
        assertNotNull(user.getId());

        User found = userMapper.selectById(user.getId());
        assertNotNull(found);
        assertEquals(user.getUsername(), found.getUsername());

        userMapper.deleteById(user.getId());
        assertNull(userMapper.selectById(user.getId()));
    }
}
