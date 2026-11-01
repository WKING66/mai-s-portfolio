package dev.amai.portfolio.portfolio.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import dev.amai.portfolio.portfolio.entity.domain.ProjectDO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** project 的 MyBatis-Plus 映射。 */
@Mapper
public interface ProjectMapper extends BaseMapper<ProjectDO> {
    /** 条件在数据库分页前执行，total 与当前页使用同一组管理筛选。 */
    Page<ProjectDO> selectListPage(
            Page<ProjectDO> page,
            @Param("status") Integer status,
            @Param("keyword") String keyword);
}
