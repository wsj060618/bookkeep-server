package com.bookkeep.web.aspect;

import com.bookkeep.common.exception.BusinessException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Pointcut;
import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

@Aspect
@Component
@Slf4j
@RequiredArgsConstructor
public class LogAspect {

    private static final Pattern SENSITIVE_PATTERN = Pattern.compile(
            "\"(password|oldPassword|newPassword|token)\"\\s*:\\s*\"[^\"]*\"");

    private final ObjectMapper objectMapper;

    @Pointcut("execution(public * com.bookkeep.web.controller..*.*(..))")
    public void controllerPointcut() {
    }

    @Around("controllerPointcut()")
    public Object around(ProceedingJoinPoint joinPoint) throws Throwable {
        String method = joinPoint.getSignature().getDeclaringTypeName() + "." + joinPoint.getSignature().getName();
        String params = toJson(joinPoint.getArgs());
        long start = System.currentTimeMillis();
        Object result;
        try {
            result = joinPoint.proceed();
        } catch (BusinessException e) {
            // 业务异常（400/401/403）也按 ERROR 留痕，保证 error 日志里能看到每一次失败；
            // 但不打堆栈——它是预期内的分支，堆栈只会淹没真正的 5xx
            log.error("[业务异常] {} 参数:{} 耗时:{}ms 异常:{}",
                    method, params, System.currentTimeMillis() - start, e.getMessage());
            throw e;
        } catch (Throwable e) {
            // 未预期异常：必须带堆栈（最后一个参数传 e 而不是 e.getMessage()），否则 500 无从排查
            log.error("[接口异常] {} 参数:{} 耗时:{}ms",
                    method, params, System.currentTimeMillis() - start, e);
            throw e;
        }
        long cost = System.currentTimeMillis() - start;
        if (cost > 1000) {
            log.warn("[接口慢调用] {} 参数:{} 耗时:{}ms", method, params, cost);
        } else {
            log.info("[接口调用] {} 参数:{} 耗时:{}ms", method, params, cost);
        }
        return result;
    }

    private String toJson(Object[] args) {
        if (args == null || args.length == 0) {
            return "[]";
        }
        try {
            String json = objectMapper.writeValueAsString(args);
            return SENSITIVE_PATTERN.matcher(json).replaceAll("\"$1\":\"***\"");
        } catch (Exception e) {
            // 绝不能退回 Arrays.toString(args)：那会把 password 明文打进日志
            return "[参数序列化失败]";
        }
    }
}
