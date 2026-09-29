package dev.amai.portfolio.storage;

/** 成功写入后的存储侧结果；不包含对浏览器公开的访问地址。 */
public record StoredObject(String objectKey, long byteSize, String eTag) {
}
