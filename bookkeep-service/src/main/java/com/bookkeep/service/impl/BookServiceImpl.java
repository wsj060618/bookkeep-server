package com.bookkeep.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.bookkeep.common.exception.BusinessException;
import com.bookkeep.common.result.ResultCode;
import com.bookkeep.common.util.SecurityUtil;
import com.bookkeep.model.dto.req.BookCreateReq;
import com.bookkeep.model.dto.req.BookUpdateReq;
import com.bookkeep.model.dto.resp.BookResp;
import com.bookkeep.model.dto.resp.BookTrashResp;
import com.bookkeep.model.dto.resp.CategoryTemplateResp;
import com.bookkeep.model.dto.resp.ShareLinkResp;
import com.bookkeep.model.entity.Book;
import com.bookkeep.model.entity.Category;
import com.bookkeep.model.entity.User;
import com.bookkeep.service.BookService;
import com.bookkeep.service.CategoryTemplateService;
import com.bookkeep.service.constant.CategoryPresets;
import com.bookkeep.service.mapper.BookMapper;
import com.bookkeep.service.mapper.CategoryMapper;
import com.bookkeep.service.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * 账本业务实现（PLAN §5.2）。
 * <p>三条规则：
 * <ol>
 *   <li>归属一律按 {@code book.user_id} 校验；别人的账本回「不存在」，不暴露 id 是否有效</li>
 *   <li>删除 = 进回收站（{@code deleted=1 + deleted_at}），15 天内可恢复，到期才彻底删除</li>
 *   <li>始终至少留一本：删到最后一本直接 400，否则其它接口（分类/统计/流水）会全部无账本可用</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BookServiceImpl implements BookService {

    /** 每用户账本上限：单机自用够用，同时挡住脚本刷库 */
    private static final int MAX_BOOKS = 10;

    /** 回收站保留天数 */
    public static final int RETENTION_DAYS = 15;

    /** 公示链接有效期：每次分享重新计时，过期即 404 */
    public static final int SHARE_LINK_DAYS = 3;

    private final BookMapper bookMapper;
    private final CategoryMapper categoryMapper;
    private final UserMapper userMapper;
    private final CategoryTemplateService categoryTemplateService;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public List<BookResp> list() {
        // 顺手清过期的：本机不保证 7×24 开着，定时任务可能在停机期间错过
        purgeExpired();
        return doList();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public BookResp create(BookCreateReq req) {
        User user = requireUser();
        long count = bookMapper.selectCount(Wrappers.<Book>query().eq("user_id", user.getId()));
        if (count >= MAX_BOOKS) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "账本最多 " + MAX_BOOKS + " 本，先删掉不用的");
        }

        // 先取条目再落库：模板 id 不合法（别人的 / 不存在）时直接抛，不留半本账本
        List<CategoryTemplateResp.Item> items = categoryTemplateService.itemsOf(req.templateId());
        // null / 0 都是内置模板，落库存 NULL（列注释：NULL = 内置）
        Long templateId = req.templateId() == null || req.templateId() == CategoryPresets.BUILTIN_ID
                ? null : req.templateId();

        Book book = new Book();
        book.setUserId(user.getId());
        book.setName(normalizeName(req.name()));
        book.setType(CategoryPresets.BOOK_CLASS);
        book.setCategoryTemplateId(templateId);
        // 公示 token 不在这里发：必须由用户点「分享」显式生成，链接才有 3 天有效期可言
        bookMapper.insert(book);

        // 与注册同一套写法：逐条 insert（同一事务、同一 SqlSession），不要用静态 Db.saveBatch
        for (CategoryTemplateResp.Item item : items) {
            Category category = new Category();
            category.setBookId(book.getId());
            category.setName(item.name());
            category.setType(item.type());
            category.setIcon(item.icon());
            category.setColor(item.color());
            category.setSortOrder(item.sortOrder());
            categoryMapper.insert(category);
        }

        // 新建即切换：用户刚建完账本，下一屏就该是它
        updateCurrentBookId(user.getId(), book.getId());
        log.info("新建账本：userId={}, bookId={}, name={}, templateId={}, categories={}",
                user.getId(), book.getId(), book.getName(), templateId, items.size());
        return BookResp.from(book, book.getId());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ShareLinkResp share(Long bookId) {
        User user = requireUser();
        String token = UUID.randomUUID().toString().replace("-", "");
        LocalDateTime expiresAt = LocalDateTime.now().plusDays(SHARE_LINK_DAYS);

        // 归属条件写进 WHERE：知道别人的 id 也改不到，且只有一行受影响
        int updated = bookMapper.update(null, Wrappers.<Book>update()
                .eq("id", bookId)
                .eq("user_id", user.getId())
                .set("share_token", token)
                .set("share_token_expires_at", expiresAt));
        if (updated == 0) {
            throw new BusinessException(ResultCode.NOT_FOUND, "账本不存在");
        }

        // 每次分享都换新 token：旧链接立即失效（3 天是按最后一次分享算，不是按建账本算）
        log.info("生成公示链接：userId={}, bookId={}, 有效期至 {}", user.getId(), bookId, expiresAt);
        return new ShareLinkResp(token, expiresAt);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public BookResp rename(Long id, BookUpdateReq req) {
        User user = requireUser();
        Book book = requireOwned(id, user.getId());

        String name = normalizeName(req);
        // 只 set 会变的列：更新整行容易把 shareToken 覆盖成 null
        bookMapper.update(null, Wrappers.<Book>update()
                .eq("id", id)
                .set("name", name));

        book.setName(name);
        log.info("修改账本名：userId={}, bookId={}, name={}", user.getId(), id, name);
        return BookResp.from(book, user.getCurrentBookId());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public List<BookResp> switchCurrent(Long id) {
        User user = requireUser();
        requireOwned(id, user.getId());
        updateCurrentBookId(user.getId(), id);
        log.info("切换当前账本：userId={}, bookId={}", user.getId(), id);
        return doList();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        User user = requireUser();
        requireOwned(id, user.getId());

        long active = bookMapper.selectCount(Wrappers.<Book>query().eq("user_id", user.getId()));
        if (active <= 1) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "至少保留一本账本，先新建一本再删这本");
        }

        // 不能用 deleteById：它只写 deleted=1，不会写 deleted_at，回收站就算不出到期时间
        bookMapper.update(null, Wrappers.<Book>update()
                .eq("id", id)
                .set("deleted", 1)
                .set("deleted_at", LocalDateTime.now()));

        if (Objects.equals(user.getCurrentBookId(), id)) {
            // 删的是当前账本：重指到剩下最早的一本，否则所有接口都会「没有当前账本」
            Book next = bookMapper.selectOne(Wrappers.<Book>query()
                    .eq("user_id", user.getId())
                    .orderByAsc("id")
                    .last("LIMIT 1"));
            updateCurrentBookId(user.getId(), next.getId());
        }
        log.info("账本移入回收站：userId={}, bookId={}", user.getId(), id);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public List<BookTrashResp> trash() {
        purgeExpired();
        Long userId = SecurityUtil.getUserId();
        return bookMapper.selectTrash(userId).stream()
                .map(book -> BookTrashResp.from(book, RETENTION_DAYS))
                .toList();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public BookResp restore(Long id) {
        Long userId = SecurityUtil.getUserId();
        // 恢复必须走原生 SQL：MP 的逻辑删除会让 update 自动带上 deleted=0，永远改不到已删的行
        if (bookMapper.restore(id, userId) == 0) {
            throw new BusinessException(ResultCode.NOT_FOUND, "账本不存在或不在回收站");
        }
        User user = requireUser();
        Book book = bookMapper.selectById(id);
        log.info("从回收站恢复账本：userId={}, bookId={}", userId, id);
        return BookResp.from(book, user.getCurrentBookId());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void purge(Long id) {
        Long userId = SecurityUtil.getUserId();
        // 只允许删「自己回收站里」的：用一句手写查询定身份，活着的一律 404（防误删在用账本）
        if (bookMapper.selectTrashedId(id, userId) == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "账本不存在或不在回收站");
        }
        // 顺序与定时清理一致：流水 → 分类 → 账本（从子表往父表删，不会报外键错）
        bookMapper.hardDeleteTransactions(id);
        bookMapper.hardDeleteCategories(id);
        bookMapper.hardDeleteBook(id);
        log.info("彻底删除账本：userId={}, bookId={}（连同分类与流水）", userId, id);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int purgeExpired() {
        LocalDateTime deadline = LocalDateTime.now().minusDays(RETENTION_DAYS);
        List<Long> expired = bookMapper.selectExpiredIds(deadline);
        for (Long bookId : expired) {
            // 先删子表再删主表：将来若真加了外键，这个顺序也不会报错
            bookMapper.hardDeleteTransactions(bookId);
            bookMapper.hardDeleteCategories(bookId);
            bookMapper.hardDeleteBook(bookId);
        }
        if (!expired.isEmpty()) {
            log.info("回收站到期清理：彻底删除 {} 本账本（含分类与流水），bookIds={}", expired.size(), expired);
        }
        return expired.size();
    }

    private List<BookResp> doList() {
        User user = requireUser();
        return bookMapper.selectList(Wrappers.<Book>query()
                        .eq("user_id", user.getId())
                        .orderByAsc("id"))
                .stream()
                .map(book -> BookResp.from(book, user.getCurrentBookId()))
                .toList();
    }

    private User requireUser() {
        User user = userMapper.selectById(SecurityUtil.getUserId());
        if (user == null) {
            // 注销后旧 token 仍可能在有效期内（过滤器不查库），这里要拦住
            throw new BusinessException(ResultCode.UNAUTHORIZED, "用户不存在或已注销");
        }
        return user;
    }

    private Book requireOwned(Long id, Long userId) {
        Book book = bookMapper.selectById(id);
        if (book == null || !book.getUserId().equals(userId)) {
            throw new BusinessException(ResultCode.NOT_FOUND, "账本不存在");
        }
        return book;
    }

    private void updateCurrentBookId(Long userId, Long bookId) {
        userMapper.update(null, Wrappers.<User>update()
                .eq("id", userId)
                .set("current_book_id", bookId));
    }

    private String normalizeName(BookUpdateReq req) {
        return normalizeName(req.name());
    }

    private String normalizeName(String rawName) {
        String name = rawName.trim();
        // @NotBlank 挡不住全空格，trim 之后才是最终值
        if (name.isEmpty()) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "账本名不能为空");
        }
        return name;
    }
}