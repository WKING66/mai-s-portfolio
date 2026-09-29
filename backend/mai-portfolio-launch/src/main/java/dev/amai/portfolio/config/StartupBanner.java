package dev.amai.portfolio.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.web.server.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component
public class StartupBanner {
    private static final Logger LOG = LoggerFactory.getLogger(StartupBanner.class);

    @EventListener(ApplicationReadyEvent.class)
    public void onReady(ApplicationReadyEvent event) {
        if (!(event.getApplicationContext() instanceof ServletWebServerApplicationContext context)) {
            return;
        }
        Environment environment = context.getEnvironment();
        boolean docsEnabled = environment.getProperty("springdoc.api-docs.enabled", Boolean.class, true)
            && environment.getProperty("springdoc.swagger-ui.enabled", Boolean.class, true);
        boolean sslEnabled = environment.getProperty("server.ssl.enabled", Boolean.class, false);
        LOG.info("\n{}", formatBanner(context.getWebServer().getPort(),
            context.getServletContext().getContextPath(), docsEnabled, sslEnabled));
    }

    String formatBanner(int port, String contextPath, boolean docsEnabled, boolean sslEnabled) {
        String base = (sslEnabled ? "https://" : "http://") + "127.0.0.1:" + port + contextPath;
        String docs = docsEnabled
            ? "Knife4j  " + base + "/doc.html\nOpenAPI  " + base + "/v3/api-docs"
            : "接口文档  当前配置未开放";
        return "==================================================\n"
            + "  mai-portfolio 后端启动成功\n"
            + "  " + docs.replace("\n", "\n  ") + "\n"
            + "==================================================";
    }
}
