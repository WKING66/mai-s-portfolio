package dev.amai.portfolio.system.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "portfolio.bootstrap")
public record BootstrapProperties(boolean enabled, String initialPassword) {
}
