package com.bookkeep.common.result;

import lombok.Getter;

@Getter
public enum ResultCode {

    SUCCESS(200, "success"),
    BAD_REQUEST(400, "参数错误"),
    UNAUTHORIZED(401, "未登录或 token 失效"),
    FORBIDDEN(403, "无权访问"),
    NOT_FOUND(404, "接口不存在"),
    ERROR(500, "服务器异常");

    private final int code;
    private final String message;

    ResultCode(int code, String message) {   // 构造器默认 private
        this.code = code;
        this.message = message;
    }

}