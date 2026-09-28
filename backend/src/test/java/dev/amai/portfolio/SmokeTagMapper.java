package dev.amai.portfolio;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import java.util.Map;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

@Mapper
interface SmokeTagMapper {
    @Select("SELECT id, name FROM tag WHERE slug LIKE 'smoke-test-%' ORDER BY id")
    IPage<Map<String, Object>> findSmokeTags(Page<Map<String, Object>> page);
}
