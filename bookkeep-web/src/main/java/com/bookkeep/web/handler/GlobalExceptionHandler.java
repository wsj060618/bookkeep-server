package com.bookkeep.web.handler;

import com.bookkeep.common.exception.BusinessException;
import com.bookkeep.common.result.Result;
import com.bookkeep.common.result.ResultCode;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * 全局异常处理：所有响应统一为 {@code {code, message, data}}。
 * <p>HTTP 状态码固定 200，业务语义全部放在 body 的 {@code code} 里（前端拦截器按 code 判断）。
 * <p>日志约定：4xx 类（客户端问题）打 warn 不打堆栈；未预期异常打 error 并带完整堆栈。
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /** 业务异常：code / message 由抛出方决定。 */
    @ExceptionHandler(BusinessException.class)
    public Result<Void> handleBusiness(BusinessException e) {
        log.warn("业务异常: code={}, message={}", e.getCode(), e.getMessage());
        return Result.error(e.getCode(), e.getMessage());
    }

    /** 请求体 / 表单参数校验失败（Spring 6 中 MethodArgumentNotValidException 是 BindException 的子类）。 */
    @ExceptionHandler(BindException.class)
    public Result<Void> handleBind(BindException e) {
        FieldError fieldError = e.getBindingResult().getFieldError();
        String message = fieldError == null
                ? "参数校验失败"
                : fieldError.getField() + ": " + fieldError.getDefaultMessage();
        log.warn("参数校验失败: {}", message);
        return Result.error(ResultCode.BAD_REQUEST, message);
    }

    /** 方法参数上的 @Validated 校验失败（@RequestParam / @PathVariable）。 */
    @ExceptionHandler(ConstraintViolationException.class)
    public Result<Void> handleConstraintViolation(ConstraintViolationException e) {
        String message = e.getConstraintViolations().stream()
                .findFirst()
                .map(v -> v.getPropertyPath() + ": " + v.getMessage())
                .orElse("参数校验失败");
        log.warn("参数校验失败: {}", message);
        return Result.error(ResultCode.BAD_REQUEST, message);
    }

    /** JSON 语法错误、字段类型不匹配等。 */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public Result<Void> handleNotReadable(HttpMessageNotReadableException e) {
        log.warn("请求体解析失败: {}", e.getMessage());
        return Result.error(ResultCode.BAD_REQUEST, "请求体格式错误");
    }

    /** 缺少必填参数 / 参数类型不匹配。 */
    @ExceptionHandler({MissingServletRequestParameterException.class, MethodArgumentTypeMismatchException.class})
    public Result<Void> handleBadParameter(Exception e) {
        String message;
        if (e instanceof MissingServletRequestParameterException ex) {
            message = "缺少参数: " + ex.getParameterName();
        } else if (e instanceof MethodArgumentTypeMismatchException ex) {
            message = "参数类型错误: " + ex.getName();
        } else {
            message = "参数错误";
        }
        log.warn("参数错误: {}", message);
        return Result.error(ResultCode.BAD_REQUEST, message);
    }

    /** 路径不存在（Spring 6.1 由静态资源处理器抛出；若开启 throwExceptionIfNoHandlerFound 则是另一种异常）。 */
    @ExceptionHandler({NoResourceFoundException.class, NoHandlerFoundException.class})
    public Result<Void> handleNotFound(Exception e) {
        String path = e instanceof NoResourceFoundException ex
                ? ex.getResourcePath()
                : ((NoHandlerFoundException) e).getRequestURL();
        log.warn("接口不存在: /{}", path);
        return Result.error(ResultCode.NOT_FOUND);
    }

    /** 兜底：不向客户端泄露内部信息，完整堆栈只进日志。 */
    @ExceptionHandler(Exception.class)
    public Result<Void> handleUnknown(Exception e) {
        log.error("未处理异常", e);
        return Result.error(ResultCode.ERROR);
    }
}
