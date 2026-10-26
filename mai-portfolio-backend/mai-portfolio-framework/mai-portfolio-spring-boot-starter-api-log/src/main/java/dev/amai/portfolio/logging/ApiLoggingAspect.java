package dev.amai.portfolio.logging;

import dev.amai.portfolio.logging.autoconfigure.ApiLogProperties;
import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.servlet.HandlerMapping;

@Aspect
public class ApiLoggingAspect {
    private static final Logger LOG = LoggerFactory.getLogger(ApiLoggingAspect.class);
    private final SensitivePayloadSanitizer sensitivePayloadSanitizer;
    private final ApiLogProperties apiLogProperties;

    public ApiLoggingAspect(
            SensitivePayloadSanitizer sensitivePayloadSanitizer,
            ApiLogProperties apiLogProperties) {
        this.sensitivePayloadSanitizer = sensitivePayloadSanitizer;
        this.apiLogProperties = apiLogProperties;
    }

    /** 按既有顺序记录 Controller 请求与结果；业务调用最多执行一次，捕获的异常原样传播。 */
    @Around(
            "@within(dev.amai.portfolio.logging.annotation.ApiLog) "
                    + "|| @annotation(dev.amai.portfolio.logging.annotation.ApiLog)")
    public Object logControllerCall(ProceedingJoinPoint invocation) throws Throwable {
        // 1. 日志关闭或没有 Servlet 请求时直接执行原调用，不读取入参。
        if (!apiLogProperties.enabled()) {
            return invocation.proceed();
        }
        ServletRequestAttributes requestAttributes =
                (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (requestAttributes == null) {
            return invocation.proceed();
        }
        // 2. 提取匹配路由和参数名，入参先脱敏再写请求日志。
        HttpServletRequest request = requestAttributes.getRequest();
        Object routePattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        String route =
                routePattern instanceof String pattern
                        ? pattern
                        : invocation.getSignature().getName();
        String method = request.getMethod();
        MethodSignature signature = (MethodSignature) invocation.getSignature();
        Map<String, String> arguments = new LinkedHashMap<>();
        String[] parameterNames = signature.getParameterNames();
        Object[] argumentValues = invocation.getArgs();
        for (int index = 0; index < argumentValues.length; index++) {
            String parameterName =
                    parameterNames != null && index < parameterNames.length
                            ? parameterNames[index]
                            : "arg" + index;
            arguments.put(
                    parameterName,
                    sensitivePayloadSanitizer.sanitize(parameterName, argumentValues[index]));
        }
        LOG.info("API request method={} route={} args={}", method, route, arguments);
        // 3. 请求日志之后开始计时，保持原有耗时边界；只调用一次目标方法。
        long startedAtNanos = System.nanoTime();
        try {
            Object result = invocation.proceed();
            // 4. 成功时脱敏结果；此 try 同时覆盖目标方法及响应日志，失败仍按原异常抛出。
            LOG.info(
                    "API response method={} route={} elapsedMs={} result={}",
                    method,
                    route,
                    TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAtNanos),
                    sensitivePayloadSanitizer.sanitize("result", result));
            return result;
        } catch (Throwable failure) {
            // 错误消息可能含凭据或原始请求内容；这里只记录异常类别，由统一异常处理器决定响应。
            LOG.warn(
                    "API failed method={} route={} elapsedMs={} errorType={}",
                    method,
                    route,
                    TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAtNanos),
                    failure.getClass().getSimpleName());
            throw failure;
        }
    }
}
