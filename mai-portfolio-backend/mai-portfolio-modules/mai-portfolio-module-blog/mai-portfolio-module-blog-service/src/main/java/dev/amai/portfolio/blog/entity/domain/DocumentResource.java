package dev.amai.portfolio.blog.entity.domain;

import static dev.amai.portfolio.blog.constant.DocumentPolicy.*;

import dev.amai.portfolio.blog.enums.*;
import java.util.regex.Pattern;

/** 稳定引用不含 OSS 地址；READY 必须绑定 Asset，失败记录只能保留来源与状态。 */
public record DocumentResource(
        String key,
        ResourceType type,
        ResourceSource source,
        String originalUri,
        Long assetId,
        ResourceStatus status) {
    public static final String REFERENCE_PREFIX = "resource://";
    private static final Pattern RESOURCE_KEY =
            Pattern.compile("[A-Za-z0-9_-]{1," + MAX_RESOURCE_KEY_LENGTH + "}");

    public DocumentResource {
        if (key == null
                || !RESOURCE_KEY.matcher(key).matches()
                || type == null
                || source == null
                || status == null
                || originalUri != null && originalUri.length() > MAX_RESOURCE_URI_LENGTH
                || status == ResourceStatus.READY && (assetId == null || assetId < 1)
                || status != ResourceStatus.READY && assetId != null) {
            throw new IllegalArgumentException("Invalid document resource");
        }
    }

    public String reference() {
        return REFERENCE_PREFIX + key;
    }
}
