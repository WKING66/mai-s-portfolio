package dev.amai.portfolio.asset.api;

import java.util.Set;

/** 文件内容校验与生命周期；身份及业务绑定由调用域负责。 */
public interface AssetFileService {
    long upload(AssetUsage usage, long uploader, String filename, String contentType, byte[] bytes);

    AssetFileContent openReady(long assetId);

    /** 仅用于未绑定的新资源补偿；持锁检查全部业务引用后才允许删除，不依赖物理外键。 */
    void discardUnbound(Set<Long> newlyCreatedIds, long uploader);
}
