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
 * <p>为什么要多查一次 book，而不是直接用 current_book_id：
 * 用户手里有账本时确实不多，但「拿别人的账本号」是系统的安全边界。
 * 多一次按主键的查询换来「所有写接口都过这一道」，比让每个 Service 各写一遍越权判断划算。
 * <p>只读路径走 {@link #currentBookId()}，写路径必须走 {@link #writableBookId()}——
 * 后者会拦住已归档（学期结束）的账本。
 */
@Service
@RequiredArgsConstructor
public class CurrentBookServiceImpl implements CurrentBookService {

    private final UserMapper userMapper;
    private final BookMapper bookMapper;

    @Override
    @Transactional(readOnly = true)
    public Long currentBookId() {
        return requireBook().getId();
    }

    @Override
    @Transactional(readOnly = true)
    public Long writableBookId() {
        Book book = requireBook();
        if (book.getArchived() != null && book.getArchived() == 1) {
            throw new BusinessException(ResultCode.BAD_REQUEST,
                    "当前账本已归档（学期结束），只读；先取消归档再记账或改分类");
        }
        return book.getId();
    }

    /** 当前用户 + 当前账本，两个都不存在/不归属就抛 401/400 */
    private Book requireBook() {
        Long userId = SecurityUtil.getUserId();

        User user = userMapper.selectById(userId);
        if (user == null) {
            // 注销后的 token 仍可能在有效期内（过滤器不查库），这里要拦住
            throw new BusinessException(ResultCode.UNAUTHORIZED, "用户不存在或已注销");
        }
        if (user.getCurrentBookId() == null) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "当前账号还没有账本");
        }

        Book book = bookMapper.selectById(user.getCurrentBookId());
        if (book == null || !book.getUserId().equals(userId)) {
            // 正常流程下不会发生（注册时一起建的），但真被人改了库就是错，401 也不放越权通过
            throw new BusinessException(ResultCode.UNAUTHORIZED, "账本不存在或不属于当前用户");
        }
        return book;
    }
}