package com.bookkeep.service.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.bookkeep.model.entity.Transaction;
import com.bookkeep.service.mapper.row.CategoryAmountRow;
import com.bookkeep.service.mapper.row.MonthSumRow;
import com.bookkeep.service.mapper.row.TransactionBalanceRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * 流水 mapper。
 * <p>手写 SQL 全部放在 {@code bookkeep-service/src/main/resources/mapper/TransactionMapper.xml}
 * （namespace 与本接口全限定名一致），接口里只留方法签名。
 * <p>为什么这一段用 XML 而不是 {@code @Select} 注解：这里的 SQL 带动态条件、窗口函数，
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

    /** 截至某天（含）的累计余额：导出时算「期初结余」和「期末结余」。 */
    BigDecimal sumBalanceUpTo(@Param("bookId") Long bookId, @Param("date") LocalDate date);

    /** 某月收入 / 支出合计（{@code month} 为 yyyy-MM）。 */
    MonthSumRow sumMonth(@Param("bookId") Long bookId, @Param("month") String month);

    /** 某月某类型的分类占比原料，金额降序。 */
    List<CategoryAmountRow> selectCategoryAmounts(@Param("bookId") Long bookId,
                                                  @Param("month") String month,
                                                  @Param("type") String type);

    /**
     * 日期区间内的流水 + 逐行结余，日期升序。
     * <p>区间两端都可为 null（等于不限）；结余始终按<strong>全账本</strong>累计，不会因为加了日期筛选就从 0 重新算。
     */
    List<TransactionBalanceRow> selectRangeWithRunningBalance(@Param("bookId") Long bookId,
                                                              @Param("startDate") LocalDate startDate,
                                                              @Param("endDate") LocalDate endDate);

    /** 某月的流水 + 逐行结余（公示页用），日期升序。 */
    List<TransactionBalanceRow> selectMonthWithRunningBalance(@Param("bookId") Long bookId,
                                                              @Param("month") String month);
}