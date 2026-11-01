package com.bookkeep.service.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.bookkeep.model.entity.Transaction;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.math.BigDecimal;

/**
 * 流水 mapper。
 * <p>手写 SQL 全部放在 {@code bookkeep-service/src/main/resources/mapper/TransactionMapper.xml}
 * （namespace 与本接口全限定名一致），接口里只留方法签名。
 * <p>为什么这一段用 XML 而不是 {@code @Select} 注解：这里的 SQL 带动态条件、将来还要写窗口函数，
 * 注解里得用 {@code <script>} 包裹并把 {@code <>} 转义成 {@code &lt;&gt;}，既难读 IDE 又不认。
 * <p><strong>注意</strong>：MyBatis-Plus 只对**它自己生成的 SQL** 自动拼 {@code deleted = 0}；
 * 这里手写的 SQL 必须自己写，否则已删除的流水会被算进余额（PLAN §8 第 6 条）。
 * <p>{@code @Mapper} 与主启动类的 {@code @MapperScan("com.bookkeep.service.mapper")} 不冲突：
 * Spring Boot 只在**没有** {@code @MapperScan} 时才自动扫描 {@code @Mapper}，
 * 所以这里它纯粹是给 IDE（MyBatisX 靠它把 XML 和接口关联起来）和读者看的标记。
 */
@Mapper
public interface TransactionMapper extends BaseMapper<Transaction> {

    /** 当前账本余额 = Σ收入 − Σ支出（只算未删除行）。 */
    BigDecimal sumBalance(@Param("bookId") Long bookId);

    /**
     * 同上，但排除某一条流水——修改流水时用。
     * <p>为什么不用「一个方法 + 可空 excludeId」：那样 XML 里要写 {@code <if test="...">}，
     * 空参数只能传 null，MyBatisX 对「多参数 + 动态标签」还会误报；拆成两条，参数一一对应更清楚。
     */
    BigDecimal sumBalanceExcluding(@Param("bookId") Long bookId, @Param("excludeId") Long excludeId);

    /** 某个分类下还有多少笔未删除的流水（删除分类前检查引用）。 */
    long countByCategoryId(@Param("categoryId") Long categoryId);
}