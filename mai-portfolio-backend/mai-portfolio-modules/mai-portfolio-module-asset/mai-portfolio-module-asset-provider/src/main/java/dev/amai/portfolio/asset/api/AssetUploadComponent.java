package dev.amai.portfolio.asset.api;

/** 跨业务资产上传能力；通用 OSS 写入、摘要、元数据及状态生命周期只实现一次。 */
public interface AssetUploadComponent {
    /** 返回 READY 资产 ID；失败不返回成功，既有对象不覆盖、不自动删除。 */
    long upload(AssetUploadRequest request);
}
