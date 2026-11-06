package com.bookkeep.service.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.bookkeep.model.entity.PasswordResetToken;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * 找回密码 token。
 * <p>作废用两条手写 UPDATE：一张表只有「未用 / 已用」两种状态，不需要逻辑删除，
 * 手写语句也比 Wrapper 更直白（一次改掉该用户所有未用 token）。
 */
public interface PasswordResetTokenMapper extends BaseMapper<PasswordResetToken> {

    /** 重新申请或重置成功后：把该用户所有未用 token 一起作废 */
    @Update("UPDATE password_reset_token SET used_at = NOW() WHERE user_id = #{userId} AND used_at IS NULL")
    int invalidateAll(@Param("userId") Long userId);

    /** 注销账户时直接清掉该用户的 token 记录（用户已物理删除，留着属于孤儿数据） */
    @org.apache.ibatis.annotations.Delete("DELETE FROM password_reset_token WHERE user_id = #{userId}")
    int deleteByUser(@Param("userId") Long userId);

    /** 标记单个 token 已使用（配合 condition 里的 used_at IS NULL 做到「只能用一次」） */
    @Update("UPDATE password_reset_token SET used_at = NOW() WHERE id = #{id} AND used_at IS NULL")
    int markUsed(@Param("id") Long id);
}