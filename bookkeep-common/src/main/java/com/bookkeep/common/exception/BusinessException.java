package com.bookkeep.common.exception;

import com.bookkeep.common.result.ResultCode;

public class BusinessException extends RuntimeException {

    private final ResultCode resultCode;

    /** 只给自定义文案，状态码默认 400 */
    public BusinessException(String message) {
        super(message);
        this.resultCode = ResultCode.BAD_REQUEST;
    }

    /** 只给状态码，文案用枚举自带的 */
    public BusinessException(ResultCode resultCode) {
        super(resultCode.getMessage());
        this.resultCode = resultCode;
    }

    /** 状态码 + 自定义文案 */
    public BusinessException(ResultCode resultCode, String message) {
        super(message);
        this.resultCode = resultCode;
    }

    public ResultCode getResultCode() {
        return resultCode;
    }

    public Integer getCode() {
        return resultCode.getCode();
    }
}