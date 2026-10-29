package com.bookkeep.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.toolkit.Db;
import com.bookkeep.common.exception.BusinessException;
import com.bookkeep.common.result.ResultCode;
import com.bookkeep.service.AuthService;
import com.bookkeep.service.constant.CategoryTemplate;
import com.bookkeep.model.dto.req.RegisterReq;
import com.bookkeep.model.dto.resp.UserResp;
import com.bookkeep.model.entity.Book;
import com.bookkeep.model.entity.Category;
import com.bookkeep.model.entity.User;
import com.bookkeep.service.mapper.BookMapper;
import com.bookkeep.service.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Locale;

/**
 * 认证业务实现
 * <p>{@link PasswordEncoder} 用接口而不是 {@code BCryptPasswordEncoder}：
 * Bean 由 web 模块的 {@code SecurityConfig} 提供（service 模块不重复定义密码策略），
 * 编译期只依赖 {@code spring-security-crypto}（随 bookkeep-common 的 spring-security-core 传递进来）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthServiceImpl implements AuthService {

    private final UserMapper userMapper;
    private final BookMapper bookMapper;
    private final PasswordEncoder passwordEncoder;

    /**
     * 注册：建用户 → 建首本账本 → 预置分类 → 回写 current_book_id
     * <p>全部写操作在同一个事务里，任一步失败整单回滚——绝不能出现「有用户没账本」或「账本只有一半分类」。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public UserResp register(RegisterReq req) {
        String username = req.username().trim();
        // 账本类型先统一成小写再校验，避免 "Personal" / " PERSONAL " 这类写法把数据写脏；
        // CategoryTemplate.of 同时承担「非法类型直接 400」的职责，并返回对应的分类模板
        String bookType = req.bookType().trim().toLowerCase(Locale.ROOT);
        List<CategoryTemplate.Seed> seeds = CategoryTemplate.of(bookType);

        // 用户名预检：只是为了给出友好提示，真正的唯一性由 user.username 唯一索引保证
        // 注意 MP 的逻辑删除会自动给这里拼 deleted = 0，已软删的用户查不出来，
        // 但它依然占着唯一索引，所以下面还要 catch 一次 DuplicateKeyException
        if (userMapper.selectCount(Wrappers.<User>query().eq("username", username)) > 0) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "用户名已存在");
        }

        // 1) 用户：密码只存 BCrypt 哈希；昵称留空则默认用用户名，前端不用再判空
        User user = new User();
        user.setUsername(username);
        user.setPassword(passwordEncoder.encode(req.password()));
        user.setNickname(StringUtils.hasText(req.nickname()) ? req.nickname().trim() : username);
        user.setEmail(StringUtils.hasText(req.email()) ? req.email().trim() : null);
        try {
            userMapper.insert(user);
        } catch (DuplicateKeyException e) {
            // 并发注册同一用户名，或该用户名被逻辑删除的历史用户占着 → 统一按业务异常处理
            throw new BusinessException(ResultCode.BAD_REQUEST, "用户名已存在");
        }

        // 2) 第一本账本：注册必带，所以注册完用户一定有一本账本
        Book book = new Book();
        book.setUserId(user.getId());
        book.setName(req.bookName().trim());
        book.setType(bookType);
        bookMapper.insert(book);

        // 3) 预置分类模板：Db.saveBatch 内部走 JDBC batch，且用 Spring 管理的 SqlSessionFactory，
        //    所以与上面两次 insert 属于同一个事务，一起提交 / 一起回滚
        List<Category> categories = seeds.stream()
                .map(seed -> toCategory(book.getId(), seed))
                .toList();
        Db.saveBatch(categories);

        // 4) 回写当前账本：只更新 current_book_id 一列，
        //    不用 updateById（那会把密码哈希、用户名等字段再写一遍，白白多冒一次覆盖风险）
        userMapper.update(null, Wrappers.<User>update()
                .eq("id", user.getId())
                .set("current_book_id", book.getId()));
        user.setCurrentBookId(book.getId());

        log.info("注册成功：userId={}, username={}, bookId={}, bookType={}, 预置分类 {} 条",
                user.getId(), username, book.getId(), bookType, categories.size());
        return UserResp.from(user);
    }

    /** 模板条目 → 分类实体（只做字段映射，图标/颜色存的是语义 key） */
    private Category toCategory(Long bookId, CategoryTemplate.Seed seed) {
        Category category = new Category();
        category.setBookId(bookId);
        category.setName(seed.name());
        category.setType(seed.type());
        category.setIcon(seed.icon());
        category.setColor(seed.color());
        category.setSortOrder(seed.sortOrder());
        return category;
    }
}
