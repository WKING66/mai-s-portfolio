package dev.amai.portfolio.asset.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import dev.amai.portfolio.asset.entity.domain.MediaAssetDO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface MediaAssetMapper extends BaseMapper<MediaAssetDO> {
    /** 共享资产引用清单；在持有资产行锁后以新语句检查，防止并发绑定被补偿删除。 */
    boolean isReferenced(@Param("id") long id);
}
