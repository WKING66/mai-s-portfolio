package dev.amai.portfolio.portfolio.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import dev.amai.portfolio.portfolio.entity.domain.ProjectDO;
import org.apache.ibatis.annotations.Mapper;

/** project 的 MyBatis-Plus 映射。 */
@Mapper
public interface ProjectMapper extends BaseMapper<ProjectDO> { }
