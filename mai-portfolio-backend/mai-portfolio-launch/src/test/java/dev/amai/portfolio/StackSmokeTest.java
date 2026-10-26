package dev.amai.portfolio;

import static org.assertj.core.api.Assertions.assertThat;

import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.alibaba.druid.pool.DruidDataSource;
import org.junit.jupiter.api.Test;
import org.springdoc.core.configuration.SpringDocConfiguration;

class StackSmokeTest {
    @Test
    void selectedLibrariesAreOnClasspath() {
        assertThat(StpUtil.class).isNotNull();
        assertThat(BaseMapper.class).isNotNull();
        assertThat(DruidDataSource.class).isNotNull();
        assertThat(SpringDocConfiguration.class).isNotNull();
    }
}
