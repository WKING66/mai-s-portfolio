package dev.amai.portfolio.service;

public interface OwnerAccessService {
    /** 每次调用都检查数据库中的当前角色和状态，不仅信任已有会话。 */
    OwnerIdentity requireOwner();
}
