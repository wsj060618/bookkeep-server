package com.bookkeep.service.support;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.bookkeep.common.exception.BusinessException;
import com.bookkeep.common.result.ResultCode;
import com.bookkeep.model.entity.ShareLink;
import com.bookkeep.model.entity.ShareLinkBook;
import com.bookkeep.service.mapper.ShareLinkBookMapper;
import com.bookkeep.service.mapper.ShareLinkMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 公示链接的唯一解析口（PLAN §5.9 / §10.9）。
 * <p>为什么单独抽出来：公示页正文和「公示页票据」两条路都要「token → 账本列表」，
 * 各写一遍就会出现两套时钟（一个查 {@code expires_at}、一个忘查），过期链接在某个入口还能看图。
 * <p>**过期与不存在给同一个 404**，不暴露「这个 token 曾经存在过」。
 */
@Component
@RequiredArgsConstructor
public class ShareLinkResolver {

    private final ShareLinkMapper shareLinkMapper;
    private final ShareLinkBookMapper shareLinkBookMapper;

    /** token → 有效的链接；不存在/过期/成员为空一律 404 */
    public ShareLink requireValid(String token) {
        ShareLink link = token == null ? null : shareLinkMapper.selectOne(Wrappers.<ShareLink>query()
                .eq("token", token)
                .gt("expires_at", LocalDateTime.now()));
        if (link == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "公示页不存在或已失效");
        }
        if (bookIds(link.getId()).isEmpty()) {
            throw new BusinessException(ResultCode.NOT_FOUND, "公示页不存在或已失效");
        }
        return link;
    }

    /** 链接覆盖的账本 id，顺序即用户勾选顺序 */
    public List<Long> bookIds(Long shareLinkId) {
        return shareLinkBookMapper.selectList(Wrappers.<ShareLinkBook>query()
                        .eq("share_link_id", shareLinkId)
                        .orderByAsc("sort_order", "id"))
                .stream()
                .map(ShareLinkBook::getBookId)
                .toList();
    }
}