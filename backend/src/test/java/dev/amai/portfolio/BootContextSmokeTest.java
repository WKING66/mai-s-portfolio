package dev.amai.portfolio;

import static org.assertj.core.api.Assertions.assertThat;

import com.alibaba.druid.pool.DruidDataSource;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:postgresql://127.0.0.1:65535/unused",
    "spring.datasource.username=unused",
    "spring.datasource.password=unused",
    "spring.datasource.druid.initial-size=0",
    "spring.flyway.enabled=false",
    "springdoc.api-docs.enabled=true"
})
class BootContextSmokeTest {
    @Autowired
    private DataSource dataSource;

    @Test
    void bootFourContextStartsWithSelectedLibraries() {
        assertThat(dataSource).isInstanceOf(DruidDataSource.class);
        // Full PostgreSQL + Druid behavior is checked separately against the development database.
    }
}
