package com.bookkeep.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 流水票据（小票/收据照片，PLAN §10.9）。
 * <p><strong>没有 {@code deleted} 字段</strong>：票据不单独做逻辑删除，流水进回收站时票据跟着留着，
 * 账本/账号被物理删除时才连文件一起清。所以这张表是「物理删除」语义，别给它加 {@code @TableLogic}。
 * <p>{@code storedPath} 是相对 {@code app.receipt.dir} 的路径，**唯一事实来源**；
 * 目录名（账本名 + bookId）只是为了让人在磁盘上看得懂，账本改名不搬目录。
 */
@Data
@TableName("transaction_receipt")
public class TransactionReceipt {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long transactionId;

    /** 用户可见的标题，≤30 字；默认取原始文件名去扩展名 */
    private String title;

    /** 上传时的文件名，只展示，不参与任何路径拼接（防穿越） */
    private String originalName;

    /** 相对路径：{安全化账本名}_{bookId}/{yyyy-MM}/{uuid}.{ext} */
    private String storedPath;

    /** 按魔数识别：image/jpeg | image/png | image/webp */
    private String contentType;

    private Long sizeBytes;

    private LocalDateTime createdAt;
}