package dev.amai.portfolio;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/** 后端启动入口；扫描各业务模块及配置属性，不在启动类编排业务初始化。 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class PortfolioApplication {
    public static void main(String[] args) {
        SpringApplication.run(PortfolioApplication.class, args);
    }
}
