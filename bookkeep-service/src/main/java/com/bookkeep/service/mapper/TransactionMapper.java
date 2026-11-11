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
 * 流水 mapper。手写 SQL 在 resources/mapper/TransactionMapper.xml。
 * <p>手写 SQL 必须自己写 deleted = 0（MP 只给自动生成的 SQL 加）。
 */
@Mapper
public interface TransactionMapper extends BaseMapper<Transaction> {

    /** 当前账本余额 = Σ收入 − Σ支出 */
    BigDecimal sumBalance(@Param("bookId") Long bookId);

    /** 同上，排除某一条（改流水时用） */
    BigDecimal sumBalanceExcluding(@Param("bookId") Long bookId, @Param("excludeId") Long excludeId);

    /** 某个分类下还有多少笔未删除的流水（删除分类前检查引用）。 */
    long countByCategoryId(@Param("categoryId") Long categoryId);

    /** 截至某天（含）的累计余额：导出时算「期初结余」和「期末结余」。 */
    BigDecimal sumBalanceUpTo(@Param("bookId") Long bookId, @Param("date") LocalDate date);

    /** 日期区间收入 / 支出合计，两端可空（null = 不限）。 */
    MonthSumRow sumRange(@Param("bookId") Long bookId,
                         @Param("startDate") LocalDate startDate,
                         @Param("endDate") LocalDate endDate);

    /** 日期区间的分类占比原料，两端可空（null = 不限）。 */
    List<CategoryAmountRow> selectCategoryAmountsRange(@Param("bookId") Long bookId,
                                                       @Param("startDate") LocalDate startDate,
                                                       @Param("endDate") LocalDate endDate,
                                                       @Param("type") String type);

    /**
     * 日期区间内的流水 + 逐行结余，日期升序。
     * <p>区间两端都可为 null（等于不限）；结余始终按<strong>全账本</strong>累计，不会因为加了日期筛选就从 0 重新算。
     */
    List<TransactionBalanceRow> selectRangeWithRunningBalance(@Param("bookId") Long bookId,
                                                              @Param("startDate") LocalDate startDate,
                                                              @Param("endDate") LocalDate endDate);
}