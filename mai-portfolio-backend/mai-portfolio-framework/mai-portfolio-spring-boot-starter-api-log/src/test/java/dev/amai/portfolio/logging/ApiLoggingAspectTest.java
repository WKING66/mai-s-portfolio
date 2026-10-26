package dev.amai.portfolio.logging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.amai.portfolio.logging.autoconfigure.ApiLogProperties;
import java.util.List;
import java.util.Map;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.reflect.MethodSignature;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.servlet.HandlerMapping;
import tools.jackson.databind.json.JsonMapper;

class ApiLoggingAspectTest {
    private final ApiLogProperties apiLogProperties = new ApiLogProperties(true, 2048, List.of());
    private final SensitivePayloadSanitizer sensitivePayloadSanitizer =
            new SensitivePayloadSanitizer(JsonMapper.builder().build(), apiLogProperties);

    @AfterEach
    void resetRequestContext() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void disabledLoggingReturnsOriginalResultFromOneInvocation() throws Throwable {
        ProceedingJoinPoint invocation = mock(ProceedingJoinPoint.class);
        Object expectedResult = new Object();
        when(invocation.proceed()).thenReturn(expectedResult);
        ApiLoggingAspect apiLoggingAspect =
                new ApiLoggingAspect(
                        sensitivePayloadSanitizer, new ApiLogProperties(false, 2048, List.of()));

        assertThat(apiLoggingAspect.logControllerCall(invocation)).isSameAs(expectedResult);
        verify(invocation, times(1)).proceed();
    }

    @Test
    void nonServletInvocationReturnsOriginalResultFromOneInvocation() throws Throwable {
        ProceedingJoinPoint invocation = mock(ProceedingJoinPoint.class);
        Object expectedResult = new Object();
        when(invocation.proceed()).thenReturn(expectedResult);

        assertThat(
                        new ApiLoggingAspect(sensitivePayloadSanitizer, apiLogProperties)
                                .logControllerCall(invocation))
                .isSameAs(expectedResult);
        verify(invocation, times(1)).proceed();
    }

    @Test
    void servletInvocationPreservesResultIdentityAndInput() throws Throwable {
        ProceedingJoinPoint invocation = servletInvocation();
        Map<String, Object> expectedResult = Map.of("projectName", "portfolio");
        when(invocation.proceed()).thenReturn(expectedResult);

        assertThat(
                        new ApiLoggingAspect(sensitivePayloadSanitizer, apiLogProperties)
                                .logControllerCall(invocation))
                .isSameAs(expectedResult);
        assertThat(invocation.getArgs()).containsExactly("test-password", "portfolio");
        verify(invocation, times(1)).proceed();
    }

    @Test
    void servletFailureRethrowsSameExceptionWithoutRetry() throws Throwable {
        ProceedingJoinPoint invocation = servletInvocation();
        RuntimeException expectedFailure = new IllegalStateException("test failure");
        when(invocation.proceed()).thenThrow(expectedFailure);

        assertThatThrownBy(
                        () ->
                                new ApiLoggingAspect(sensitivePayloadSanitizer, apiLogProperties)
                                        .logControllerCall(invocation))
                .isSameAs(expectedFailure);
        verify(invocation, times(1)).proceed();
    }

    private ProceedingJoinPoint servletInvocation() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/probe");
        request.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, "/probe");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        MethodSignature methodSignature = mock(MethodSignature.class);
        when(methodSignature.getParameterNames()).thenReturn(new String[] {"password", "title"});
        ProceedingJoinPoint invocation = mock(ProceedingJoinPoint.class);
        when(invocation.getSignature()).thenReturn(methodSignature);
        when(invocation.getArgs()).thenReturn(new Object[] {"test-password", "portfolio"});
        return invocation;
    }
}
