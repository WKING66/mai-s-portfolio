package dev.amai.portfolio.asset.api;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** 媒体资产域对外提供的只读能力。 */
public interface AssetQueryService {
    /** 绑定写事务内按 ID 升序锁定并校验 READY；调用方在同事务建立引用。 */
    Map<Long, AssetMetadataData> lockReady(Set<Long> assetIds);

    Optional<AssetMetadataData> findReadyMetadata(Long assetId);

    Optional<AssetResourceData> findReady(Long assetId, AssetType expectedType);

    /** 项目列表等批量投影只查询一次资产表，不逐项产生数据库查询。 */
    Map<Long, AssetResourceData> findReady(Set<Long> assetIds, AssetType expectedType);
}
