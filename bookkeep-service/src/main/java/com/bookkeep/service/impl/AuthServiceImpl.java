package com.bookkeep.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.bookkeep.common.exception.BusinessException;
import com.bookkeep.common.result.ResultCode;
import com.bookkeep.common.util.JwtUtil;
import com.bookkeep.common.util.SecurityUtil;
import com.bookkeep.service.AuthService;
import com.bookkeep.service.constant.CategoryTemplate;
import com.bookkeep.model.dto.req.LoginReq;
import com.bookkeep.model.dto.req.RegisterReq;
import com.bookkeep.model.dto.resp.BookResp;
import com.bookkeep.model.dto.resp.LoginResp;
import com.bookkeep.model.dto.resp.UserResp;
import com.bookkeep.model.entity.Book;
import com.bookkeep.model.entity.Category;
import com.bookkeep.model.entity.User;
import com.bookkeep.service.mapper.BookMapper;
import com.bookkeep.service.mapper.CategoryMapper;
import com.bookkeep.service.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.List;

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
    private final CategoryMapper categoryMapper;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;

    /**
     * 注册：建用户 → 建首本账本 → 预置分类 → 回写 current_book_id
     * <p>全部写操作在同一个事务里，任一步失败整单回滚——绝不能出现「有用户没账本」或「账本只有一半分类」。
     * <p>用户名只 trim、<strong>不做大小写归一</strong>：{@code user.username} 是
     * {@code utf8mb4_0900_as_cs}，Tom / tom 是两个账号。大小写语义由列定义保证，不依赖库默认排序规则。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public UserResp register(RegisterReq req) {
        String username = req.username().trim();
        String email = req.email().trim();
        // 只有一种账本类型（班级），模板固定取 class；请求里不再有 bookType 字段
        List<CategoryTemplate.Seed> seeds = CategoryTemplate.classCategories();

        // 预检只是为了给出友好提示，真正的唯一性由生成列唯一索引保证：
        // 用户名 → uk_user_username_active，邮箱 → uk_user_email_active（都是「仅未删除行」）
        if (userMapper.selectCount(Wrappers.<User>query().eq("username", username)) > 0) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "用户名已存在");
        }
        if (userMapper.selectCount(Wrappers.<User>query().eq("email", email)) > 0) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "邮箱已被注册");
        }

        // 1) 用户：密码只存 BCrypt 哈希；邮箱是登录凭据，用户名只是显示名
        User user = new User();
        user.setUsername(username);
        user.setPassword(passwordEncoder.encode(req.password()));
        user.setEmail(email);
        try {
            userMapper.insert(user);
        } catch (DuplicateKeyException e) {
            // 并发注册同一用户名/邮箱（都通过了预检）→ 统一按业务异常处理。
            // 墓碑行不会走到这里：它们在生成列里是 NULL
            throw new BusinessException(ResultCode.BAD_REQUEST, "用户名或邮箱已被注册");
        }

        // 2) 班级账本：注册必建，名字可空（为空用默认「班级账本」）
        Book book = new Book();
        book.setUserId(user.getId());
        book.setName(StringUtils.hasText(req.bookName())
                ? req.bookName().trim()
                : CategoryTemplate.DEFAULT_BOOK_NAME);
        book.setType(CategoryTemplate.BOOK_CLASS);
        // 公示链接不在这里建：用户点「分享」才生成，链接只活 3 天（PLAN §5.9）
        bookMapper.insert(book);

        // 3) 预置分类模板：逐条 insert，和用户/账本共用同一个 SqlSession 与事务。
        //    这里**不用** MyBatis-Plus 的 Db.saveBatch：它是静态工具，自己取 Session，
        //    在「外层已有事务」的场景下写入不一定挂在当前事务上——测试里就表现为
        //    「日志显示插了 13 条，同一事务却查不到」（MySQL 默认 REPEATABLE READ，
        //    事务快照在第一次读时已固定，另一个连接后提交的行对它是不可见的）。
        //    13 条固定数据，逐条 insert 的性能代价可以忽略，换来事务语义确定、代码更好读。
        List<Category> categories = seeds.stream()
                .map(seed -> toCategory(book.getId(), seed))
                .toList();
        categories.forEach(categoryMapper::insert);

        // 4) 回写当前账本：只更新 current_book_id 一列，
        //    不用 updateById（那会把密码哈希、用户名等字段再写一遍，白白多冒一次覆盖风险）
        userMapper.update(null, Wrappers.<User>update()
                .eq("id", user.getId())
                .set("current_book_id", book.getId()));
        user.setCurrentBookId(book.getId());

        log.info("注册成功：userId={}, username={}, bookId={}, 预置班级分类 {} 条",
                user.getId(), username, book.getId(), categories.size());
        return UserResp.from(user);
    }

    /**
     * 登录：校验密码 → 签发 token → 带上用户信息和全部账本
     * <p>「用户不存在」和「密码错误」返回同一句提示：否则接口就成了用户名探测器。
     * 被逻辑删除的用户由 MP 自动拼 {@code deleted = 0} 查不出来，同样走这条分支。
     * <p>只读事务：这条路径不写库（current_book_id 由注册/切账本维护，登录不改它）。
     */
    @Override
    @Transactional(readOnly = true)
    public LoginResp login(LoginReq req) {
        String email = req.email().trim();

        // 邮箱未删除行内唯一（uk_user_email_active），所以能直接定位到唯一账号
        User user = userMapper.selectOne(Wrappers.<User>query().eq("email", email));
        if (user == null || !passwordEncoder.matches(req.password(), user.getPassword())) {
            // 与「密码错」同一句：不让接口变成邮箱是否注册过的探测器
            throw new BusinessException(ResultCode.BAD_REQUEST, "邮箱或密码错误");
        }

        // 账本按 id 升序，第一本（注册时建的）排在最前，前端切换列表的顺序才稳定
        List<BookResp> books = bookMapper.selectList(Wrappers.<Book>query()
                        .eq("user_id", user.getId())
                        .orderByAsc("id"))
                .stream()
                .map(book -> BookResp.from(book, user.getCurrentBookId()))
                .toList();

        log.info("登录成功：userId={}, email={}, 账本 {} 本", user.getId(), email, books.size());
        return new LoginResp(jwtUtil.generateToken(user.getId(), user.getTokenVersion()),
                UserResp.from(user), books);
    }

    /**
     * 当前登录用户（GET /api/auth/me）
     * <p>只读事务；userId 来自 SecurityContext，不再解析 token。
     * <p>{@code selectById} 会被 MyBatis-Plus 自动加上 {@code deleted = 0}：
     * 用户注销后旧 token 在 7 天有效期内仍能通过过滤器（过滤器只比对 token_version，查不到就放行认证），但这里查不到 →
     * 按 401 处理，客户端会清 token 回登录页。
     */
    @Override
    @Transactional(readOnly = true)
    public UserResp getCurrentUser() {
        Long userId = SecurityUtil.getUserId();
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new BusinessException(ResultCode.UNAUTHORIZED, "用户不存在或已注销");
        }
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
