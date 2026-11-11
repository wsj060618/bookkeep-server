package com.bookkeep.service;

import com.bookkeep.model.dto.resp.PublicReportResp;

/**
 * 免登录公示页：凭分享 token 读，不接 userId，响应里不含任何个人信息。
 */
public interface PublicReportService {

    /** startMonth / endMonth 为 yyyy-MM，都可空（都不传 = 全部时间） */
    PublicReportResp report(String shareToken, String startMonth, String endMonth);
}
