package com.bookkeep.service.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.bookkeep.model.entity.ShareLink;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Param;

public interface ShareLinkMapper extends BaseMapper<ShareLink> {

    /** 重新分享前清掉旧链接（含成员关系）：一个账号只保留一条有效链接，泄漏时再分享一次即可作废 */
    @Delete("DELETE FROM share_link_book WHERE share_link_id IN "
            + "(SELECT id FROM share_link WHERE user_id = #{userId})")
    int deleteBookshipsByUser(@Param("userId") Long userId);

    @Delete("DELETE FROM share_link WHERE user_id = #{userId}")
    int deleteByUser(@Param("userId") Long userId);

    /** 账本彻底删除后，把链接里已经指向不存在账本的成员行清掉 */
    @Delete("DELETE FROM share_link_book WHERE book_id = #{bookId}")
    int deleteBookshipsByBook(@Param("bookId") Long bookId);

    /** 成员被清空（或只剩回收站里的账本）后，链接本身也没意义了 */
    @Delete("DELETE FROM share_link WHERE id NOT IN (SELECT DISTINCT share_link_id FROM share_link_book)")
    int deleteOrphanLinks();
}