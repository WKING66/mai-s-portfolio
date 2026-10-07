package dev.amai.portfolio.portfolio.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import dev.amai.portfolio.portfolio.entity.domain.ProjectMediaDO;
import org.apache.ibatis.annotations.Mapper;

/** 项目资源关系只由 Portfolio 持有，不跨模块操作 Asset Mapper。 */
@Mapper
public interface ProjectMediaMapper extends BaseMapper<ProjectMediaDO> { }
