package dev.amai.portfolio.system.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import dev.amai.portfolio.system.entity.domain.UserAccountDO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface UserAccountMapper extends BaseMapper<UserAccountDO> {
    /** 在当前事务内串行化站长账号初始化，防止多实例同时执行检查后插入。 */
    @Select("SELECT 1 FROM pg_advisory_xact_lock(7137426010001)")
    int acquireBootstrapLock();

    /** 同一账号的资料/密码写入串行化；必须在事务中调用，不锁定其他账号。 */
    @Select("SELECT id, username, type, password_hash, status, created_at, updated_at "
        + "FROM user_account WHERE id = #{accountId} FOR UPDATE")
    UserAccountDO selectForUpdate(@Param("accountId") long accountId);
}
