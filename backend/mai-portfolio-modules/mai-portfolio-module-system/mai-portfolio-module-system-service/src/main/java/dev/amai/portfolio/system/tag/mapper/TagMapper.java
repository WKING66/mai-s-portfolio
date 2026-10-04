package dev.amai.portfolio.system.tag.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import dev.amai.portfolio.system.tag.entity.domain.TagDO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface TagMapper extends BaseMapper<TagDO> {
    /** 在当前事务内串行化内置技术标签初始化，防止并发重复插入。 */
    @Select("SELECT 1 FROM pg_advisory_xact_lock(7137426010003)")
    int acquireBootstrapLock();
}
