package dev.amai.portfolio.storage;

/**
 * 二进制对象存储边界。
 *
 * <p>业务层只依赖此接口，不接触本地路径、OSS 凭据或厂商客户端。</p>
 */
public interface ObjectStorage {
    StoredObject store(ObjectStorageWriteRequest request);

    StoredObjectContent open(String objectKey);

    boolean exists(String objectKey);

    void delete(String objectKey);
}
