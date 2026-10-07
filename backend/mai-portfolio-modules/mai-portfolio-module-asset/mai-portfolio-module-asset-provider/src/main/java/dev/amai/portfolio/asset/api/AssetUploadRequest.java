package dev.amai.portfolio.asset.api;

/**
 * 已通过所属业务内容校验的内部上传命令，不直接绑定 HTTP。
 * prefix/filename 由服务端决定；上传本身不授予公开读取权限。
 * 图片必须先解码重编码；文档导入方负责相应格式及大小策略。
 */
public record AssetUploadRequest(AssetType type, String prefix, String filename,
        String contentType, byte[] content, Integer width, Integer height) {
}
