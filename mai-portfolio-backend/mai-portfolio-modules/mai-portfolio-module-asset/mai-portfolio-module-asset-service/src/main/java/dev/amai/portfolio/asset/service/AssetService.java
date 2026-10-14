package dev.amai.portfolio.asset.service;

import dev.amai.portfolio.asset.api.AssetUsage;
import dev.amai.portfolio.asset.entity.vo.AssetVo;
import org.springframework.web.multipart.MultipartFile;

/** 上传只创建资产，不执行头像、封面或文档引用绑定。 */
public interface AssetService {
    AssetVo resource(Long assetId);

    AssetVo upload(MultipartFile file, AssetUsage usage);
}
