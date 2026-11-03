package dev.amai.portfolio.security.rate;

import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import dev.amai.portfolio.web.advice.GlobalExceptionHandler;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.GetMapping;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import dev.amai.portfolio.redis.rate.RateLimitStrategy;
import dev.amai.portfolio.redis.rate.RedisRateLimiter;
import dev.amai.portfolio.web.exception.ApiErrorCode;
import dev.amai.portfolio.web.exception.ApiException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

class RateLimitAspectTest {
    private final RedisRateLimiter rateLimiter = mock(RedisRateLimiter.class);
    private final AtomicInteger calls = new AtomicInteger();
    private Endpoints endpoints;

    @BeforeEach
    void setup() {
        var request = new MockHttpServletRequest();
        request.setRemoteAddr("127.0.0.1");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        var proxy = new AspectJProxyFactory(new Endpoints(calls));
        proxy.addAspect(new RateLimitAspect(rateLimiter));
        endpoints = proxy.getProxy();
    }

    @AfterEach
    void clearRequest() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void bareAnnotationAppliesDefaultsAndCallsBusinessOnlyWhenAllowed() {
        when(rateLimiter.fixedWindow(anyString(), eq("127.0.0.1"), eq(5), eq(300))).thenReturn(true, false);
        assertThat(endpoints.login()).isEqualTo("ok");
        assertThatThrownBy(() -> endpoints.login()).isInstanceOfSatisfying(ApiException.class,
                error -> assertThat(error.code()).isEqualTo(ApiErrorCode.RATE_LIMITED));
        assertThat(calls).hasValue(1);
        verify(rateLimiter, never()).tokenBucket(any(), any(), anyInt(), anyDouble());
    }

    @Test
    void httpDenialUsesExistingGlobalErrorEnvelope() throws Exception {
        when(rateLimiter.fixedWindow(anyString(), anyString(), anyInt(), anyInt())).thenReturn(false);
        var mvc = MockMvcBuilders.standaloneSetup(endpoints)
                .setControllerAdvice(new GlobalExceptionHandler()).build();
        mvc.perform(get("/limited"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"))
                .andExpect(jsonPath("$.message")
                        .value("请求过于频繁，请稍后重试"));
        assertThat(calls).hasValue(0);
    }

    @Test
    void strategyUsesOnlyTokenBucketConfigurationAndKeepsEndpointIdentity() {
        when(rateLimiter.tokenBucket(anyString(), eq("127.0.0.1"), eq(8), eq(0.5))).thenReturn(true);
        assertThat(endpoints.chat()).isEqualTo("chat");
        verify(rateLimiter).tokenBucket(contains(".chat()"), eq("127.0.0.1"), eq(8), eq(0.5));
        verify(rateLimiter, never()).fixedWindow(any(), any(), anyInt(), anyInt());
    }

    @Test
    void failureOrMissingHttpContextCannotEnterBusiness() {
        when(rateLimiter.fixedWindow(anyString(), anyString(), anyInt(), anyInt()))
                .thenThrow(new IllegalStateException("Redis unavailable"));
        assertThatThrownBy(() -> endpoints.login()).hasMessage("Redis unavailable");
        RequestContextHolder.resetRequestAttributes();
        assertThatThrownBy(() -> endpoints.login()).isInstanceOf(IllegalStateException.class);
        assertThat(calls).hasValue(0);
    }

    @RestController
    static class Endpoints {
        private final AtomicInteger calls;

        Endpoints(AtomicInteger calls) {
            this.calls = calls;
        }

        @RateLimit
        @GetMapping("/limited")
        public String login() {
            calls.incrementAndGet();
            return "ok";
        }

        @RateLimit(strategy = RateLimitStrategy.TOKEN_BUCKET, capacity = 8, refillRate = 0.5)
        public String chat() {
            calls.incrementAndGet();
            return "chat";
        }
    }
}
