package com.bookkeep.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.bookkeep.common.exception.BusinessException;
import com.bookkeep.common.result.ResultCode;
import com.bookkeep.common.util.SecurityUtil;
import com.bookkeep.model.dto.req.BookUpdateReq;
import com.bookkeep.model.dto.resp.BookResp;
import com.bookkeep.model.entity.Book;
import com.bookkeep.service.BookService;
import com.bookkeep.service.CurrentBookService;
import com.bookkeep.service.mapper.BookMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 账本业务实现：只做重命名（PLAN §5.2 明确不做新建 / 删除 / 切换）。
 * <p>归属校验按 {@code book.user_id} 查，而不是只认 current_book_id：
 * 将来真有多本账时这个接口仍然安全；别人的账本一律回「不存在」，不暴露 id 是否存在。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BookServiceImpl implements BookService {

    private final BookMapper bookMapper;
    private final CurrentBookService currentBookService;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public BookResp rename(Long id, BookUpdateReq req) {
        Long userId = SecurityUtil.getUserId();
        Book book = bookMapper.selectById(id);
        if (book == null || !book.getUserId().equals(userId)) {
            throw new BusinessException(ResultCode.NOT_FOUND, "账本不存在");
        }

        String name = req.name().trim();
        // @NotBlank 挡不住全空格，而 trim 之后就成空串了
        if (name.isEmpty()) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "账本名不能为空");
        }

        // 只 set 会变的列：更新整行容易把 shareToken 等字段覆盖成 null
        bookMapper.update(null, Wrappers.<Book>update()
                .eq("id", id)
                .set("name", name));

        book.setName(name);
        log.info("修改账本名：userId={}, bookId={}, name={}", userId, id, name);
        return BookResp.from(book, currentBookService.currentBookId());
    }
}