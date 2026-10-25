package dev.amai.portfolio.blog.entity.domain;

import static dev.amai.portfolio.blog.constant.DocumentPolicy.*;

import dev.amai.portfolio.blog.enums.*;
import java.util.regex.Pattern;

/** 稳定引用不含 OSS 地址；READY 必须绑定 Asset，失败记录保留安全来源、状态及可操作的错误原因。 */
public record DocumentResource(
        String key,

        ResourceType type,

        ResourceSource source,

        String originalUri,

        Long assetId,

        ResourceStatus status,

        String errorMessage) {
    public static final String REFERENCE_PREFIX = "resource://";

    private static final Pattern RESOURCE_KEY =
            Pattern.compile("[A-Za-z0-9_-]{1," + MAX_RESOURCE_KEY_LENGTH + "}");

    /** 历史版本未记录诊断原因时保持原有构造契约。 */
    public DocumentResource(
            String key, ResourceType type, ResourceSource source, String originalUri,
            Long assetId, ResourceStatus status) {
        this(key, type, source, originalUri, assetId, status, null);
    }

    public DocumentResource {
        if (key == null
                || !RESOURCE_KEY.matcher(key).matches()
                || type == null
                || source == null
                || status == null
                || originalUri != null && originalUri.length() > MAX_RESOURCE_URI_LENGTH
                || status == ResourceStatus.READY && (assetId == null || assetId < 1)
                || status != ResourceStatus.READY && assetId != null
                || errorMessage != null && errorMessage.length() > MAX_RESOURCE_ERROR_LENGTH
                || status == ResourceStatus.READY && errorMessage != null) {
            throw new IllegalArgumentException("Invalid document resource");
        }
    }

    /** 正文即使没改，作者重新确认网络抓取授权时仍可重试失败引用；稳定 key 必须沿用。 */
    public boolean canRetryRemote(boolean remoteRightsConfirmed) {
        return remoteRightsConfirmed && source == ResourceSource.REMOTE && status != ResourceStatus.READY;
    }

    public String reference() {
        return REFERENCE_PREFIX + key;
    }
}
