package dev.amai.portfolio.asset.convert;

import dev.amai.portfolio.asset.api.AssetMetadataData;
import dev.amai.portfolio.asset.api.AssetResourceData;
import dev.amai.portfolio.asset.api.AssetType;
import dev.amai.portfolio.asset.api.AssetUsage;
import dev.amai.portfolio.asset.entity.domain.MediaAssetDO;
import dev.amai.portfolio.asset.entity.vo.AssetVo;
import dev.amai.portfolio.asset.enums.MediaAssetType;
import dev.amai.portfolio.storage.StoredObjectAccessUrl;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.ReportingPolicy;
import org.mapstruct.factory.Mappers;

/** 资产同名字段转换；就绪校验、授权和签名生成由资产服务负责。 */
@Mapper(unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface AssetConvert {
    AssetConvert MAPPER = Mappers.getMapper(AssetConvert.class);

    AssetVo to(AssetResourceData resource);

    AssetMetadataData to(MediaAssetDO asset);

    // 存储抽象使用通用 url，HTTP 契约保留 resourceUrl，仅在此边界映射一次。
    @Mapping(target = "resourceUrl", source = "accessUrl.url")
    AssetResourceData to(MediaAssetDO asset, StoredObjectAccessUrl accessUrl);

    /** 存储编码使用资产域既有规则，不依赖枚举 ordinal。 */
    default AssetType to(Integer type) {
        return MediaAssetType.toApiType(type);
    }

    /** 缺失或非法用途继续明确失败，不把损坏元数据投影成空用途。 */
    default AssetUsage toUsage(Integer usage) {
        return AssetUsage.fromCode(usage);
    }
}
