package dev.amai.portfolio.blog.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import dev.amai.portfolio.blog.entity.domain.DocumentVersionDO;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface DocumentVersionMapper extends BaseMapper<DocumentVersionDO> {}
