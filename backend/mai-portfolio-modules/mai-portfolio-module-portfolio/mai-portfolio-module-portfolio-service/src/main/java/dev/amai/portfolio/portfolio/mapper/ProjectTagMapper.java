package dev.amai.portfolio.portfolio.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import dev.amai.portfolio.portfolio.entity.domain.ProjectTagDO;
import org.apache.ibatis.annotations.Mapper;

/** project_tag 的 MyBatis-Plus 映射。 */
@Mapper
public interface ProjectTagMapper extends BaseMapper<ProjectTagDO> { }
