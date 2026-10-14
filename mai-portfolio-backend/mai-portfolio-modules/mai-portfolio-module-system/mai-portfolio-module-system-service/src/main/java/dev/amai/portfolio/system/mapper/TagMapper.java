package dev.amai.portfolio.system.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import dev.amai.portfolio.system.entity.domain.TagDO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface TagMapper extends BaseMapper<TagDO> {
    /** 标签初始化使用独立固定事务锁编号；提交/回滚释放，不能与账号初始化锁重用。 */
    @Select("SELECT 1 FROM pg_advisory_xact_lock(7137426010003)")
    int acquireBootstrapLock();
}
