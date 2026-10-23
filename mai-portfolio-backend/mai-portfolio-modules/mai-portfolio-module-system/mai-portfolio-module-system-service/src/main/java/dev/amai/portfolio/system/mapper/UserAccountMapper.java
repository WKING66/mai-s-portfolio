package dev.amai.portfolio.system.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import dev.amai.portfolio.system.entity.domain.UserAccountDO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface UserAccountMapper extends BaseMapper<UserAccountDO> {
    /** 事务级锁编号为站长初始化的固定身份；不得与标签锁重用，也不能随意改号。 */
    @Select("SELECT 1 FROM pg_advisory_xact_lock(7137426010001)")
    int acquireBootstrapLock();

    /** 同一账号的资料/密码写入串行化；必须在事务中调用，不锁定其他账号。 */
    UserAccountDO selectForUpdate(@Param("accountId") long accountId);
}
