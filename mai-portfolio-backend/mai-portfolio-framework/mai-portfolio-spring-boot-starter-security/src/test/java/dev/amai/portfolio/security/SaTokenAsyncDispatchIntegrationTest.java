package dev.amai.portfolio.security;

import static org.assertj.core.api.Assertions.assertThat;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.dao.SaTokenDao;
import cn.dev33.satoken.dao.SaTokenDaoDefaultImpl;
import cn.dev33.satoken.stp.StpUtil;
import dev.amai.portfolio.redis.autoconfigure.RedisInfrastructureAutoConfiguration;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

/** 使用真实 Servlet 容器重现流式响应的二次派发；不模拟 Sa-Token 的线程上下文。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    classes = SaTokenAsyncDispatchIntegrationTest.TestApplication.class)
class SaTokenAsyncDispatchIntegrationTest {
    private static final String CONTENT = "# 已登录用户的流式导出\n";
    private static final String ACCOUNT = "async-export-fixture";

    @LocalServerPort
    private int port;

    @Autowired
    private DispatchProbe probe;

    private final HttpClient client = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5)).build();

    @Test
    void authenticatedStreamRetainsContextDuringAsyncRedispatch() throws Exception {
        var login = client.send(request("/fixture/login").GET().build(),
            HttpResponse.BodyHandlers.ofString());
        assertThat(login.statusCode()).isEqualTo(200);
        String cookie = login.headers().firstValue("set-cookie").orElseThrow().split(";", 2)[0];

        var export = client.send(request("/fixture/export").header("Cookie", cookie).GET().build(),
            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

        assertThat(export.statusCode()).isEqualTo(200);
        assertThat(export.body()).isEqualTo(CONTENT);
        // 响应体可能先到达客户端；同时确认收尾派发确实完成了登录校验。
        assertThat(probe.authenticatedAsync.await(5, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    void anonymousRequestCannotStartProtectedStream() throws Exception {
        var export = client.send(request("/fixture/export").GET().build(),
            HttpResponse.BodyHandlers.ofString());

        assertThat(export.statusCode()).isEqualTo(401);
        assertThat(export.body()).contains("UNAUTHENTICATED").doesNotContain(CONTENT);
    }

    private HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
            .timeout(Duration.ofSeconds(10));
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration(exclude = RedisInfrastructureAutoConfiguration.class,
        excludeName = "org.redisson.spring.starter.RedissonAutoConfigurationV4")
    @Import({FixtureController.class, DispatchProbe.class})
    static class TestApplication {
        // 隔离测试会话存储；Filter、MVC、鉴权及异步派发仍使用真实实现。
        @Bean
        SaTokenDao saTokenDao() {
            return new SaTokenDaoDefaultImpl();
        }
    }

    @RestController
    static class FixtureController {
        @GetMapping("/fixture/login")
        String login() {
            StpUtil.login(ACCOUNT);
            return "signed-in";
        }

        @SaCheckLogin
        @GetMapping("/fixture/export")
        StreamingResponseBody export() {
            return output -> output.write(CONTENT.getBytes(StandardCharsets.UTF_8));
        }
    }

    static class DispatchProbe implements WebMvcConfigurer {
        private final CountDownLatch authenticatedAsync = new CountDownLatch(1);

        @Override
        public void addInterceptors(InterceptorRegistry registry) {
            registry.addInterceptor(new HandlerInterceptor() {
                @Override
                public boolean preHandle(HttpServletRequest request, HttpServletResponse response,
                        Object handler) {
                    if (request.getDispatcherType() == DispatcherType.ASYNC) {
                        StpUtil.checkLogin();
                        if (ACCOUNT.equals(StpUtil.getLoginIdAsString())) {
                            authenticatedAsync.countDown();
                        }
                    }
                    return true;
                }
            }).addPathPatterns("/fixture/export");
        }
    }
}
