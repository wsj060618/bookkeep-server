package com.bookkeep.service.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.bookkeep.model.entity.User;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Param;

public interface UserMapper extends BaseMapper<User> {

    /**
     * 注销账户用：物理删除用户行。
     * <p>不能用 {@code deleteById}——那是逻辑删除，会留下墓碑行；注销的语义是「立即清空」。
     */
    @Delete("DELETE FROM user WHERE id = #{userId}")
    int hardDeleteById(@Param("userId") Long userId);
}