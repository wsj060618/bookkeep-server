package com.bookkeep.web.handler;

import com.bookkeep.common.exception.BusinessException;
import com.bookkeep.common.result.Result;
import com.bookkeep.common.result.ResultCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.ConstraintViolationException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.io.IOException;

/**
 * 全局异常处理：所有响应统一为 {@code {code, message, data}}。
 * <p>HTTP 状态码策略（第 7 天定稿，见 PLAN §5）：
 * <ul>
 *   <li>业务异常 → 仍是 <strong>HTTP 200</strong>，语义放在 body 的 {@code code} 里。
 *       调用方抛 {@code BusinessException} 时不必关心状态码，{@code 400/401/403} 一视同仁；</li>
 *   <li>认证/授权失败 → <strong>真实 401/403</strong>：过滤器链里的由
 *       {@link JwtAuthenticationEntryPoint} / {@link JwtAccessDeniedHandler} 输出，
 *       方法级鉴权（{@code @PreAuthorize}）抛出的 {@link AccessDeniedException} 由本类转交同一个
 *       {@link JwtAccessDeniedHandler}，保证两条路径的 JSON 一模一样。</li>
 * </ul>
 * <p>日志约定：4xx 类（客户端问题）打 warn 不打堆栈；未预期异常打 error 并带完整堆栈。
 */
@Slf4j
@RestControllerAdvice
@RequiredArgsConstructor
public class GlobalExceptionHandler {

    private final JwtAccessDeniedHandler accessDeniedHandler;

    /** 业务异常：code / message 由抛出方决定，HTTP 状态码恒为 200。 */
    @ExceptionHandler(BusinessException.class)
    public Result<Void> handleBusiness(BusinessException e) {
        log.warn("业务异常: code={}, message={}", e.getCode(), e.getMessage());
        return Result.error(e.getCode(), e.getMessage());
    }

    /**
     * 授权失败（{@code @PreAuthorize}、{@code @Secured} 等方法级鉴权抛出）。
     * <p>为什么要显式声明：下面的兜底 {@code @ExceptionHandler(Exception.class)} 会把它吞成
     * {@code 200 + code=500}。它在语义上是「登录了但没权限」，必须是真实 403 + {@code code=403}，
     * 否则前端拿到 500 只会提示「服务器异常」，排不出是权限问题。
     * <p>这里直接写响应（不走返回值），并委托给过滤器链用的同一个 403 处理器。
     */
    @ExceptionHandler(AccessDeniedException.class)
    public void handleAccessDenied(HttpServletRequest request,
                                   HttpServletResponse response,
                                   AccessDeniedException e) throws IOException {
        accessDeniedHandler.handle(request, response, e);
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
