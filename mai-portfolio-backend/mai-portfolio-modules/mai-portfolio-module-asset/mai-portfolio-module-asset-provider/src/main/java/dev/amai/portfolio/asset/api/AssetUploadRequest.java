package dev.amai.portfolio.asset.api;

/**
 * 已通过所属业务内容校验的内部上传命令，不直接绑定 HTTP。
 * 用途与上传者是业务元数据；对象路径仅由资产域生成。
 * 图片必须先解码重编码；文档导入方负责相应格式及大小策略。
 */
public record AssetUploadRequest(
        AssetUsage usage,

        long uploaderAccountId,

        String filename,

        String contentType,

        byte[] content,

        Integer width,

        Integer height) {}
