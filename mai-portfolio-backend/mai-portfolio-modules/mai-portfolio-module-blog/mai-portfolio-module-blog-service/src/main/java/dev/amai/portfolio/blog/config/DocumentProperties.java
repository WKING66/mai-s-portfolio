package dev.amai.portfolio.blog.config;

import jakarta.validation.constraints.*;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

/**
 * 文档输入与网络抓取的部署限制；正文和资源总量分别设限，不代替 AssetUsage 的单文件校验。
 * 总抓取预算属于一次正文处理，不为每张网络图片重新分配完整预算。
 */
@Component
@Validated
@ConfigurationProperties("portfolio.document")
public class DocumentProperties {
    /** UTF-8 正文字节上限；不是 Java 字符串长度，也不含本地资源总量。 */
    @Min(1024)
    @Max(16 * 1024 * 1024)
    private int maxContentBytes = 2 * 1024 * 1024;

    /** 上传的本地文件数和正文去重后的资源数分别使用该上限。 */
    @Min(1)
    @Max(200)
    private int maxResources = 50;

    /** 本次上传的本地资源合计字节上限；每个文件还要满足具体 AssetUsage 上限。 */
    @Min(1024)
    @Max(256 * 1024 * 1024)
    private int maxImportBytes = 64 * 1024 * 1024;

    /** 每次建立连接/获取连接的毫秒上限，实际还要受文档剩余抓取预算约束。 */
    @Min(100)
    @Max(30000)
    private int connectTimeoutMs = 3_000;

    /** 网络响应及本次读取的毫秒上限，不能因重定向或多张图片无限累加。 */
    @Min(100)
    @Max(30000)
    private int readTimeoutMs = 5_000;

    /** 一次正文资源解析共享的网络抓取时间预算，单位毫秒。 */
    @Min(1000)
    @Max(60000)
    private int maxFetchTotalMs = 15_000;

    public int getMaxFetchTotalMs() {
        return maxFetchTotalMs;
    }

    public void setMaxFetchTotalMs(int v) {
        maxFetchTotalMs = v;
    }

    /** 允许跟随的重定向次数；每次跳转都会重新验证目标 URI 和实际 DNS 地址。 */
    @Min(0)
    @Max(5)
    private int maxRedirects = 3;

    public int getMaxContentBytes() {
        return maxContentBytes;
    }

    public void setMaxContentBytes(int v) {
        maxContentBytes = v;
    }

    public int getMaxResources() {
        return maxResources;
    }

    public void setMaxResources(int v) {
        maxResources = v;
    }

    public int getMaxImportBytes() {
        return maxImportBytes;
    }

    public void setMaxImportBytes(int v) {
        maxImportBytes = v;
    }

    public int getConnectTimeoutMs() {
        return connectTimeoutMs;
    }

    public void setConnectTimeoutMs(int v) {
        connectTimeoutMs = v;
    }

    public int getReadTimeoutMs() {
        return readTimeoutMs;
    }

    public void setReadTimeoutMs(int v) {
        readTimeoutMs = v;
    }

    public int getMaxRedirects() {
        return maxRedirects;
    }

    public void setMaxRedirects(int v) {
        maxRedirects = v;
    }
}
