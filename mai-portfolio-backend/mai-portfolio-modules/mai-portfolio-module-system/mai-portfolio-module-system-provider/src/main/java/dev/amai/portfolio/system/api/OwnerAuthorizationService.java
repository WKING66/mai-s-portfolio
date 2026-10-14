package dev.amai.portfolio.system.api;

/** 业务域声明需要站长访问；认证域统一核对账号、凭据、角色及传输要求。 */
public interface OwnerAuthorizationService {
    void requireOwner();
}
