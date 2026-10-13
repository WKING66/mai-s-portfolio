package dev.amai.portfolio.system.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import dev.amai.portfolio.system.entity.domain.UserProfileDO;
import org.apache.ibatis.annotations.Mapper;

/** 仅 System 服务使用；写入前锁定对应 user_account 行，以串行化资料创建。 */
@Mapper
public interface UserProfileMapper extends BaseMapper<UserProfileDO> {
}
