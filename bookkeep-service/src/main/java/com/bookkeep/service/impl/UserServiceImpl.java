package com.bookkeep.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.bookkeep.common.exception.BusinessException;
import com.bookkeep.common.result.ResultCode;
import com.bookkeep.common.util.SecurityUtil;
import com.bookkeep.model.dto.req.AccountDeleteReq;
import com.bookkeep.model.dto.req.PasswordUpdateReq;
import com.bookkeep.model.dto.req.UserUpdateReq;
import com.bookkeep.model.dto.resp.UserResp;
import com.bookkeep.model.entity.User;
import com.bookkeep.service.UserService;
import com.bookkeep.service.mapper.BookMapper;
import com.bookkeep.service.mapper.PasswordResetTokenMapper;
import com.bookkeep.service.mapper.ShareLinkMapper;
import com.bookkeep.service.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * 用户自助管理实现。
 * <p>写密码的两处（这里 + {@code ResetPasswordRunner}）都必须自增 {@code token_version}，
 * 否则旧 token 能一直用到过期（PLAN §8 第 34 条）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserServiceImpl implements UserService {

    private final UserMapper userMapper;
    private final BookMapper bookMapper;
    private final PasswordResetTokenMapper tokenMapper;
    private final ShareLinkMapper shareLinkMapper;
    private final PasswordEncoder passwordEncoder;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public UserResp updateProfile(UserUpdateReq req) {
        User user = requireCurrentUser();
        String username = req.username().trim();
        String email = req.email().trim();

        boolean usernameChanged = !username.equals(user.getUsername());
        boolean emailChanged = !email.equalsIgnoreCase(user.getEmail());

        if (usernameChanged && userMapper.selectCount(Wrappers.<User>query()
                .eq("username", username).ne("id", user.getId())) > 0) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "用户名已被使用");
        }
        if (emailChanged) {
            // 邮箱是登录凭据，改它等于改登录名：必须证明自己是本人
            if (!StringUtils.hasText(req.password())
                    || !passwordEncoder.matches(req.password(), user.getPassword())) {
                throw new BusinessException(ResultCode.BAD_REQUEST, "修改邮箱需要输入当前密码");
            }
            if (userMapper.selectCount(Wrappers.<User>query()
                    .eq("email", email).ne("id", user.getId())) > 0) {
                throw new BusinessException(ResultCode.BAD_REQUEST, "邮箱已被注册");
            }
        }

        // 只 set 会变的列，不用 updateById：避免把密码、token_version 整行再写一遍
        try {
            userMapper.update(null, Wrappers.<User>update()
                    .eq("id", user.getId())
                    .set("username", username)
                    .set("email", email));
        } catch (DuplicateKeyException e) {
            // 并发下两个账号同时改成同一个名字/邮箱，由唯一索引兜底
            throw new BusinessException(ResultCode.BAD_REQUEST, "用户名或邮箱已被使用");
        }

        user.setUsername(username);
        user.setEmail(email);
        return UserResp.from(user);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updatePassword(PasswordUpdateReq req) {
        User user = requireCurrentUser();
        if (!passwordEncoder.matches(req.oldPassword(), user.getPassword())) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "旧密码不正确");
        }

        // 版本用 SQL 原子自增，别读出来 +1 再写（并发两次改密码会丢一次）
        userMapper.update(null, Wrappers.<User>update()
                .eq("id", user.getId())
                .set("password", passwordEncoder.encode(req.newPassword()))
                .setSql("token_version = token_version + 1"));

        log.info("修改密码：userId={}，旧 token 已全部失效", user.getId());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteAccount(AccountDeleteReq req) {
        User user = requireCurrentUser();
        if (!passwordEncoder.matches(req.password(), user.getPassword())) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "密码不正确");
        }

        // 注销 = 立即清空，不进回收站、不等 15 天：子表先删，账本最后删（子查询依赖 book 还在）
        bookMapper.hardDeleteTransactionsByUser(user.getId());
        bookMapper.hardDeleteCategoriesByUser(user.getId());
        int books = bookMapper.hardDeleteBooksByUser(user.getId());
        // 模板跟账号走，也必须清掉：否则注销后留下没人能访问的孤儿数据
        bookMapper.hardDeleteTemplateItemsByUser(user.getId());
        bookMapper.hardDeleteTemplatesByUser(user.getId());
        // 公示链接同理：注销后链接必须立刻打不开
        shareLinkMapper.deleteBookshipsByUser(user.getId());
        shareLinkMapper.deleteByUser(user.getId());
        tokenMapper.deleteByUser(user.getId());
        userMapper.hardDeleteById(user.getId());

        log.info("注销账户（已物理删除）：userId={}，username={}，删除账本 {} 本", user.getId(), user.getUsername(), books);
    }

    private User requireCurrentUser() {
        User user = userMapper.selectById(SecurityUtil.getUserId());
        if (user == null) {
            // token 过了版本校验但用户已被删：统一按未登录处理
            throw new BusinessException(ResultCode.UNAUTHORIZED, "用户不存在或已注销");
        }
        return user;
    }
}