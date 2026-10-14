package dev.amai.portfolio.blog.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;

import dev.amai.portfolio.blog.entity.domain.DocumentDO;

import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface DocumentMapper extends BaseMapper<DocumentDO> {}
