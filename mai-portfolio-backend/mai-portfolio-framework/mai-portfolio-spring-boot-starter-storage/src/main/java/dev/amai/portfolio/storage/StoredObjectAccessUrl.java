package dev.amai.portfolio.storage;

import java.time.Instant;

/** 临时直连地址及实际签名过期时间，不持久化到资产表。 */
public record StoredObjectAccessUrl(
        String url,

        Instant expiresAt) {}
