package dev.amai.portfolio.asset.service;

import dev.amai.portfolio.asset.api.AssetContentData;
import dev.amai.portfolio.asset.entity.vo.UploadedFileVo;
import org.springframework.web.multipart.MultipartFile;

/** HTTP 文件接收业务边界；内部其他模块直接复用组件，不通过 HTTP 自调用。 */
public interface FileUploadService {
    UploadedFileVo uploadProjectImage(MultipartFile file);
    AssetContentData openProjectImage(Long assetId);
}
