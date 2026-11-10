package com.bookkeep.service.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.bookkeep.model.entity.TransactionReceipt;
import com.bookkeep.service.mapper.row.ReceiptCountRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 票据 mapper。
 * <p>手写 SQL 自己带 {@code deleted = 0}：MyBatis-Plus 只给它自己生成的 SQL 拼逻辑删除条件
 * （PLAN §8 第 6 条）。票据表本身没有 deleted 列，但 join 出来的流水 / 账本都有，不能漏。
 */
@Mapper
public interface TransactionReceiptMapper extends BaseMapper<TransactionReceipt> {

    /** 一批流水的票据张数（列表页徽标用，避免 N+1） */
    @Select("<script>"
            + "SELECT transaction_id AS transactionId, COUNT(*) AS cnt FROM transaction_receipt "
            + "WHERE transaction_id IN "
            + "<foreach collection='transactionIds' item='id' open='(' separator=',' close=')'>#{id}</foreach> "
            + "GROUP BY transaction_id"
            + "</script>")
    List<ReceiptCountRow> countByTransactionIds(@Param("transactionIds") List<Long> transactionIds);

    /** 单笔的票据张数 */
    @Select("SELECT COUNT(*) FROM transaction_receipt WHERE transaction_id = #{transactionId}")
    long countByTransaction(@Param("transactionId") Long transactionId);

    /**
     * 某用户已占用多少字节（配额用）。
     * <p>必须只算「未删除的流水 + 未删除的账本」：回收站里的账本仍占磁盘，
     * 但用户已经看不见它们了，算进配额只会让人莫名其妙被拦住。
     */
    @Select("SELECT COALESCE(SUM(r.size_bytes), 0) FROM transaction_receipt r "
            + "JOIN `transaction` t ON t.id = r.transaction_id "
            + "JOIN book b ON b.id = t.book_id "
            + "WHERE b.user_id = #{userId} AND t.deleted = 0 AND b.deleted = 0")
    long sumSizeByUser(@Param("userId") Long userId);

    /** 账本下的票据（导出用），带日期区间筛选；两端都可为 null（= 不限） */
    @Select("<script>"
            + "SELECT r.* FROM transaction_receipt r "
            + "JOIN `transaction` t ON t.id = r.transaction_id "
            + "WHERE t.book_id = #{bookId} AND t.deleted = 0 "
            + "<if test='startDate != null'>AND t.trans_date &gt;= #{startDate} </if>"
            + "<if test='endDate != null'>AND t.trans_date &lt;= #{endDate} </if>"
            + "ORDER BY t.trans_date, t.id, r.id"
            + "</script>")
    List<TransactionReceipt> selectByBookRange(@Param("bookId") Long bookId,
                                               @Param("startDate") java.time.LocalDate startDate,
                                               @Param("endDate") java.time.LocalDate endDate);

    /** 单笔流水下的全部票据（导出「这一笔」用） */
    @Select("SELECT * FROM transaction_receipt WHERE transaction_id = #{transactionId} ORDER BY id")
    List<TransactionReceipt> selectByTransaction(@Param("transactionId") Long transactionId);

    /** 一批流水的票据（公示页把票据挂到每一行，避免 N+1） */
    @Select("<script>"
            + "SELECT * FROM transaction_receipt WHERE transaction_id IN "
            + "<foreach collection='transactionIds' item='id' open='(' separator=',' close=')'>#{id}</foreach> "
            + "ORDER BY id"
            + "</script>")
    List<TransactionReceipt> selectByTransactionIds(@Param("transactionIds") List<Long> transactionIds);

    /** 物理删除某账本下所有票据记录（账本彻底删除时用，文件在 Service 里删） */
    @org.apache.ibatis.annotations.Delete("DELETE r FROM transaction_receipt r "
            + "JOIN `transaction` t ON t.id = r.transaction_id WHERE t.book_id = #{bookId}")
    int hardDeleteByBook(@Param("bookId") Long bookId);
}