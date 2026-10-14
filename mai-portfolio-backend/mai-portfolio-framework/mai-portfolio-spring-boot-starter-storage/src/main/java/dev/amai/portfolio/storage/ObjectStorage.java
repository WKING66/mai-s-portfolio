package dev.amai.portfolio.storage;

/**
 * 二进制对象存储边界。
 *
 * <p>业务层只依赖此接口，不接触本地路径、OSS 凭据或厂商客户端。</p>
 */
public interface ObjectStorage {
    /** 写入新的不可覆盖对象；实现不关闭输入流，调用方负责输入流的生命周期。 */
    StoredObject store(ObjectStorageWriteRequest request);

    /** 为受控导出等服务端处理打开内容；调用方必须关闭返回对象，释放底层流。 */
    StoredObjectContent open(String objectKey);

    /** 浏览器直连对象存储；不读取或代理对象字节。 */
    StoredObjectAccessUrl accessUrl(String objectKey);

    /** 查询存储侧是否存在；不能代替业务授权或无覆盖的条件写入。 */
    boolean exists(String objectKey);

    /** 删除指定对象；业务绑定、权限和清理时机由上层决定。 */
    void delete(String objectKey);
}
