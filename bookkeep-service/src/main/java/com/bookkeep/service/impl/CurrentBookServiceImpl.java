package com.bookkeep.service.impl;

import com.bookkeep.common.exception.BusinessException;
import com.bookkeep.common.result.ResultCode;
import com.bookkeep.common.util.SecurityUtil;
import com.bookkeep.model.entity.Book;
import com.bookkeep.model.entity.User;
import com.bookkeep.service.CurrentBookService;
import com.bookkeep.service.mapper.BookMapper;
import com.bookkeep.service.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 当前账本的实现：userId → user.current_book_id → 校验 book 归属。
 * <p>为什么要多查一次 book 而不是直接用 current_book_id：
 * 单用户单账本时确实多余，但「按账本隔离」是这套系统的安全底座，
 * 万一以后加了切账本接口、或库里 current_book_id 被手工改坏，这里仍然挡得住越权。
 * 代价是每个请求多一条按主键的查询，这个量级（班费几十笔）完全无所谓。
 * <p>只读事务：这条路径只查不写。
 */
@Service
@RequiredArgsConstructor
public class CurrentBookServiceImpl implements CurrentBookService {

    private final UserMapper userMapper;
    private final BookMapper bookMapper;

    @Override
    @Transactional(readOnly = true)
    public Long currentBookId() {
        Long userId = SecurityUtil.getUserId();

        User user = userMapper.selectById(userId);
        if (user == null) {
            // 注销后旧 token 仍可能在有效期内（过滤器不查库），这里要拦住
            throw new BusinessException(ResultCode.UNAUTHORIZED, "用户不存在或已注销");
        }
        if (user.getCurrentBookId() == null) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "当前账号还没有账本");
        }

        Book book = bookMapper.selectById(user.getCurrentBookId());
        if (book == null || !book.getUserId().equals(userId)) {
            // 理论上不该发生（注册时一起建的），出现了就是数据被改坏，宁可 401 也不能让越权通过
            throw new BusinessException(ResultCode.UNAUTHORIZED, "账本不属于当前用户");
        }
        return book.getId();
    }
}