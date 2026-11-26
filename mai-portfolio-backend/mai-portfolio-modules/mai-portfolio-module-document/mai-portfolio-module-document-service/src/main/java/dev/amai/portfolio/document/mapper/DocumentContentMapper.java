package dev.amai.portfolio.document.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import dev.amai.portfolio.document.entity.domain.DocumentContentDO;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface DocumentContentMapper extends BaseMapper<DocumentContentDO> {}
