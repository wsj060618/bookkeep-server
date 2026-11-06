package com.bookkeep.service.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.bookkeep.model.entity.Book;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 账本表。
 * <p>回收站相关的三条 SQL 必须手写：MyBatis-Plus 的逻辑删除会给**生成的 SQL** 自动拼 {@code deleted=0}，
 * 所以「查已删的」「把已删的恢复」用 Wrapper 永远查不到/改不到，只能走原生语句。
 * <p>手写 SQL 自己负责 {@code user_id} 条件——越权校验不能指望上层。
 */
public interface BookMapper extends BaseMapper<Book> {

    @Select("SELECT * FROM book WHERE user_id = #{userId} AND deleted = 1 ORDER BY deleted_at DESC, id DESC")
    List<Book> selectTrash(@Param("userId") Long userId);

    @Update("UPDATE book SET deleted = 0, deleted_at = NULL WHERE id = #{bookId} AND user_id = #{userId} AND deleted = 1")
    int restore(@Param("bookId") Long bookId, @Param("userId") Long userId);

    /** deleted_at 为 NULL 的老数据不会被选中：宁可留着，也不要凭空删掉一本不知道什么时候删的账本 */
    @Select("SELECT id FROM book WHERE deleted = 1 AND deleted_at IS NOT NULL AND deleted_at < #{deadline}")
    List<Long> selectExpiredIds(@Param("deadline") LocalDateTime deadline);

    /**
     * 注销账户时把名下账本一次性送进回收站。
     * <p>⚠️ 现在不用了：账号注销改为「立即物理删除」，见下方 hardDelete*ByUser。
     * <p>保留注释是因为单本删除仍走 {@code deleted=1 + deleted_at} 那条路，别把两者混用。
     */
    @Update("UPDATE book SET deleted = 1, deleted_at = NOW() WHERE user_id = #{userId} AND deleted = 0")
    int softDeleteAllByUser(@Param("userId") Long userId);

    /**
     * 注销账户 = 立即清空：不受 15 天回收站限制。
     * <p>顺序必须是流水 → 分类 → 账本（子表到父表）；子查询会连着回收站里的账本一起选中。
     */
    @Delete("DELETE FROM `transaction` WHERE book_id IN (SELECT id FROM book WHERE user_id = #{userId})")
    int hardDeleteTransactionsByUser(@Param("userId") Long userId);

    @Delete("DELETE FROM category WHERE book_id IN (SELECT id FROM book WHERE user_id = #{userId})")
    int hardDeleteCategoriesByUser(@Param("userId") Long userId);

    @Delete("DELETE FROM book WHERE user_id = #{userId}")
    int hardDeleteBooksByUser(@Param("userId") Long userId);

    /** 「立即彻底删除」前先确认这本确实在**自己**的回收站里：活着的一律当作不存在 */
    @Select("SELECT id FROM book WHERE id = #{bookId} AND user_id = #{userId} AND deleted = 1")
    Long selectTrashedId(@Param("bookId") Long bookId, @Param("userId") Long userId);

    @Delete("DELETE FROM category WHERE book_id = #{bookId}")
    int hardDeleteCategories(@Param("bookId") Long bookId);

    @Delete("DELETE FROM `transaction` WHERE book_id = #{bookId}")
    int hardDeleteTransactions(@Param("bookId") Long bookId);

    @Delete("DELETE FROM book WHERE id = #{bookId} AND deleted = 1")
    int hardDeleteBook(@Param("bookId") Long bookId);
}