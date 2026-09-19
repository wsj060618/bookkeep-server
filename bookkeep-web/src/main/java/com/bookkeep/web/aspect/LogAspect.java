package com.bookkeep.web.aspect;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Pointcut;
import org.springframework.stereotype.Component;

import java.util.Arrays;
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
        } catch (Throwable e) {
            log.error("[接口异常] {} 参数:{} 耗时:{}ms 异常:{}",
                    method, params, System.currentTimeMillis() - start, e.getMessage());
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
            return Arrays.toString(args);
        }
    }
}
