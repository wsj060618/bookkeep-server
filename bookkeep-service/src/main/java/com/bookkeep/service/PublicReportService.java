package com.bookkeep.service;

import com.bookkeep.model.dto.resp.PublicReportResp;

/**
 * 只读公示页（免登录，PLAN §5.9）。
 * <p>入口是 {@code book.share_token} 而不是 userId：链接本身就是凭证，泄露只影响一本账的只读视图。
 * <p>返回体只有账本名与金额，不含任何个人信息。
 */
public interface PublicReportService {

    /**
     * @param shareToken 账本公示 token（32 位，无横线）
     * @param month      yyyy-MM，不传取当月
     */
    PublicReportResp report(String shareToken, String month);
}