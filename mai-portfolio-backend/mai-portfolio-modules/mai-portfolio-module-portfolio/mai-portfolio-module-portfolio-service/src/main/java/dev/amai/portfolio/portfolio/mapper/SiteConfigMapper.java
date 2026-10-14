package dev.amai.portfolio.portfolio.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import dev.amai.portfolio.portfolio.entity.domain.SiteConfigDO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface SiteConfigMapper extends BaseMapper<SiteConfigDO> {
    /** 在当前事务内串行化站点单例初始化，防止多实例写入重复配置。 */
    @Select("SELECT 1 FROM pg_advisory_xact_lock(7137426010002)")
    int acquireBootstrapLock();
}
