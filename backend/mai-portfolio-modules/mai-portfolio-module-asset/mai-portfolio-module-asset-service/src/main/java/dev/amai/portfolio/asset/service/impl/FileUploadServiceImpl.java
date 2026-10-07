package dev.amai.portfolio.asset.service.impl;

import dev.amai.portfolio.asset.api.AssetContentData;
import dev.amai.portfolio.asset.api.AssetContentService;
import dev.amai.portfolio.asset.api.AssetQueryService;
import dev.amai.portfolio.asset.api.AssetType;
import dev.amai.portfolio.asset.api.ImageUploadComponent;
import dev.amai.portfolio.asset.api.ImageUploadPurpose;
import dev.amai.portfolio.asset.constant.AssetMessageConstants;
import dev.amai.portfolio.asset.constant.FileUploadConstants;
import dev.amai.portfolio.asset.entity.vo.UploadedFileVo;
import dev.amai.portfolio.asset.service.FileUploadService;
import dev.amai.portfolio.web.exception.ApiErrorCode;
import dev.amai.portfolio.web.exception.ApiException;
import java.io.IOException;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

/** 读取输入流时即设上限，不根据客户端文件名、MIME 或声明大小决定安全性。 */
@Service
public class FileUploadServiceImpl implements FileUploadService {
    private final ImageUploadComponent images;
    private final AssetQueryService query;
    private final AssetContentService content;

    public FileUploadServiceImpl(ImageUploadComponent images, AssetQueryService query, AssetContentService content) {
        this.images = images;
        this.query = query;
        this.content = content;
    }

    @Override
    public UploadedFileVo uploadProjectImage(MultipartFile file) {
        if (file == null || file.isEmpty()) throw invalid();
        byte[] bytes;
        try (var input = file.getInputStream()) {
            bytes = input.readNBytes(images.maxBytes(ImageUploadPurpose.PROJECT_COVER) + 1);
        } catch (IOException failure) {
            throw new ApiException(ApiErrorCode.VALIDATION_FAILED, AssetMessageConstants.UPLOAD_INVALID);
        }
        long id = images.upload(bytes, file.getContentType(), ImageUploadPurpose.PROJECT_COVER);
        return new UploadedFileVo(id, FileUploadConstants.IMAGE_PATH + "/" + id);
    }

    @Override
    public AssetContentData openProjectImage(Long assetId) {
        if (!query.isReadyImageForPurpose(assetId, ImageUploadPurpose.PROJECT_COVER)) throw missing();
        return content.openReady(assetId, AssetType.IMAGE).orElseThrow(this::missing);
    }

    private ApiException invalid() { return new ApiException(ApiErrorCode.VALIDATION_FAILED, AssetMessageConstants.IMAGE_EMPTY); }
    private ApiException missing() { return new ApiException(ApiErrorCode.NOT_FOUND, AssetMessageConstants.IMAGE_NOT_FOUND); }
}
