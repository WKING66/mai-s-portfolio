package dev.amai.portfolio.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.web.server.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/** 在 Web 服务就绪后提示本机入口，地址基于实际监听端口和文档开关生成。 */
@Component
public class StartupBanner {
    private static final Logger LOG = LoggerFactory.getLogger(StartupBanner.class);

    @EventListener(ApplicationReadyEvent.class)
    public void onReady(ApplicationReadyEvent event) {
        if (!(event.getApplicationContext() instanceof ServletWebServerApplicationContext webApplicationContext)) {
            return;
        }
        // 只在 Servlet Web 服务就绪后打印入口，不把配置端口误当作实际监听端口。
        Environment environment = webApplicationContext.getEnvironment();
        boolean docsEnabled = environment.getProperty("springdoc.api-docs.enabled", Boolean.class, true)
            && environment.getProperty("springdoc.swagger-ui.enabled", Boolean.class, true);
        boolean sslEnabled = environment.getProperty("server.ssl.enabled", Boolean.class, false);
        LOG.info("\n{}", formatBanner(
                webApplicationContext.getWebServer().getPort(),
                webApplicationContext.getServletContext().getContextPath(),
                docsEnabled,
                sslEnabled));
    }

    String formatBanner(int port, String contextPath, boolean docsEnabled, boolean sslEnabled) {
        String baseUrl = (sslEnabled ? "https://" : "http://") + "127.0.0.1:" + port + contextPath;
        String documentationLinks = docsEnabled
            ? "Knife4j  " + baseUrl + "/doc.html\nOpenAPI  " + baseUrl + "/v3/api-docs"
            : "接口文档  当前配置未开放";
        return "==================================================\n"
            + "  mai-portfolio 后端启动成功\n"
            + "  " + documentationLinks.replace("\n", "\n  ") + "\n"
            + "==================================================";
    }
}
