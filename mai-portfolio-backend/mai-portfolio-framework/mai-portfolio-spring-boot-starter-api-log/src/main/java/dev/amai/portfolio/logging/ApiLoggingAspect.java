package dev.amai.portfolio.logging;

import dev.amai.portfolio.logging.autoconfigure.ApiLogProperties;
import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.Map;
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
    private final SensitivePayloadSanitizer sanitizer;
    private final ApiLogProperties properties;

    public ApiLoggingAspect(SensitivePayloadSanitizer sanitizer, ApiLogProperties properties) {
        this.sanitizer = sanitizer;
        this.properties = properties;
    }

    @Around("@within(dev.amai.portfolio.logging.annotation.ApiLog) "
        + "|| @annotation(dev.amai.portfolio.logging.annotation.ApiLog)")
    public Object logControllerCall(ProceedingJoinPoint invocation) throws Throwable {
        if (!properties.enabled()) {
            return invocation.proceed();
        }
        ServletRequestAttributes attributes = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attributes == null) {
            return invocation.proceed();
        }
        HttpServletRequest request = attributes.getRequest();
        Object routePattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        String route = routePattern instanceof String pattern ? pattern : invocation.getSignature().getName();
        String method = request.getMethod();
        MethodSignature signature = (MethodSignature) invocation.getSignature();
        Map<String, String> arguments = new LinkedHashMap<>();
        String[] names = signature.getParameterNames();
        Object[] values = invocation.getArgs();
        for (int index = 0; index < values.length; index++) {
            String name = names != null && index < names.length ? names[index] : "arg" + index;
            arguments.put(name, sanitizer.sanitize(name, values[index]));
        }
        LOG.info("API request method={} route={} args={}", method, route, arguments);
        long startedAt = System.nanoTime();
        try {
            Object result = invocation.proceed();
            LOG.info("API response method={} route={} elapsedMs={} result={}", method, route,
                (System.nanoTime() - startedAt) / 1_000_000, sanitizer.sanitize("result", result));
            return result;
        } catch (Throwable failure) {
            // 错误消息可能含凭据或原始请求内容；这里只记录异常类别，由统一异常处理器决定响应。
            LOG.warn("API failed method={} route={} elapsedMs={} errorType={}", method, route,
                (System.nanoTime() - startedAt) / 1_000_000, failure.getClass().getSimpleName());
            throw failure;
        }
    }
}
