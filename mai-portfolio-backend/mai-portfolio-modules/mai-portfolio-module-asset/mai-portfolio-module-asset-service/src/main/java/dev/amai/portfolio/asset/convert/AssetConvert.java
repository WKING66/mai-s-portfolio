package dev.amai.portfolio.asset.convert;

import dev.amai.portfolio.asset.api.AssetMetadataData;
import dev.amai.portfolio.asset.api.AssetResourceData;
import dev.amai.portfolio.asset.api.AssetType;
import dev.amai.portfolio.asset.api.AssetUsage;
import dev.amai.portfolio.asset.entity.domain.MediaAssetDO;
import dev.amai.portfolio.asset.entity.vo.AssetVo;
import dev.amai.portfolio.storage.StoredObjectAccessUrl;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.ReportingPolicy;
import org.mapstruct.factory.Mappers;

/** 资产元数据与接口投影；就绪状态、用途、访问授权及签名生成继续由资产服务处理。 */
@Mapper(unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface AssetConvert {
    AssetConvert MAPPER = Mappers.getMapper(AssetConvert.class);

    AssetVo to(AssetResourceData resource);

    @Mapping(target = "type", source = "type")
    @Mapping(target = "usage", source = "usage")
    AssetMetadataData to(MediaAssetDO asset, AssetType type, AssetUsage usage);

    @Mapping(target = "type", source = "type")
    @Mapping(target = "usage", source = "usage")
    @Mapping(target = "contentType", source = "asset.mimeType")
    @Mapping(target = "byteSize", expression = "java(asset.getByteSize())")
    @Mapping(target = "resourceUrl", source = "accessUrl.url")
    @Mapping(target = "expiresAt", source = "accessUrl.expiresAt")
    AssetResourceData to(
            MediaAssetDO asset,
            AssetType type,
            AssetUsage usage,
            StoredObjectAccessUrl accessUrl);
}
