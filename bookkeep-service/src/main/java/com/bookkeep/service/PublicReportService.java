package com.bookkeep.service;

import com.bookkeep.model.dto.resp.PublicReportResp;

/**
 * 免登录公示页。入口是分享 token，不是 userId；返回体不含个人信息。
 */
public interface PublicReportService {

    /** month 为 yyyy-MM，不传取当月 */
    PublicReportResp report(String shareToken, String month);
}