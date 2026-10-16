package dev.amai.portfolio.blog.component;

import dev.amai.portfolio.blog.config.DocumentProperties;
import dev.amai.portfolio.blog.entity.domain.DocumentInputResource;
import dev.amai.portfolio.blog.enums.ResourceSource;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.util.concurrent.TimeUnit;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.util.Timeout;
import org.springframework.stereotype.Component;

/** DNS 校验发生在实际建立连接的解析器内，重定向逐跳校验；不继承 Cookie/凭据/系统代理。 */
@Component
public class RemoteResourceFetcher implements AutoCloseable {
    private static final int UNSPECIFIED_PORT = -1;
    private static final int HTTP_PORT = 80;
    private static final int HTTPS_PORT = 443;
    private final DocumentProperties documentProperties;
    private final CloseableHttpClient httpClient;

    public RemoteResourceFetcher(DocumentProperties documentProperties) {
        this.documentProperties = documentProperties;
        httpClient =
                HttpClients.custom()
                        .setConnectionManager(
                                PoolingHttpClientConnectionManagerBuilder.create()
                                        .setDnsResolver(new PublicAddressDnsResolver())
                                        .build())
                        .disableRedirectHandling()
                        .disableCookieManagement()
                        .disableAutomaticRetries()
                        .setDefaultRequestConfig(
                                RequestConfig.custom()
                                        .setConnectTimeout(
                                                Timeout.ofMilliseconds(
                                                        documentProperties.getConnectTimeoutMs()))
                                        .setResponseTimeout(
                                                Timeout.ofMilliseconds(
                                                        documentProperties.getReadTimeoutMs()))
                                        .setConnectionRequestTimeout(
                                                Timeout.ofMilliseconds(
                                                        documentProperties.getConnectTimeoutMs()))
                                        .build())
                        .build();
    }

    /**
     * 只抓取已由调用方确认使用权的网络图片；重定向、读取与本篇文档其他图片共享绝对时间预算。
     * MIME 是前置筛选，真实图片解码、尺寸和重编码继续由 Asset 层负责。
     */
    public DocumentInputResource fetch(String source, int maxBytes, long deadlineNanos)
            throws IOException {
        // 1. 创建初始目标；连接建立时仍由受控 DNS Resolver 校验实际 IP。
        URI targetUri;
        try {
            targetUri = URI.create(source);
        } catch (IllegalArgumentException e) {
            throw new IOException("Invalid resource URI");
        }
        // 2. 手动跟随重定向，每一跳重新校验 URI，所有跳转共享同一绝对预算。
        for (int redirects = 0; redirects <= documentProperties.getMaxRedirects(); redirects++) {
            validateUri(targetUri);
            URI requestUri = targetUri;
            long remainingMs =
                    TimeUnit.NANOSECONDS.toMillis(
                            deadlineNanos - System.nanoTime());
            if (remainingMs < 1) {
                throw new IOException("Document fetch budget exhausted");
            }
            var httpGetRequest = new HttpGet(requestUri);
            httpGetRequest.setConfig(
                    RequestConfig.custom()
                            .setConnectTimeout(
                                    Timeout.ofMilliseconds(
                                            Math.min(
                                                    documentProperties.getConnectTimeoutMs(),
                                                    remainingMs)))
                            .setConnectionRequestTimeout(
                                    Timeout.ofMilliseconds(
                                            Math.min(
                                                    documentProperties.getConnectTimeoutMs(),
                                                    remainingMs)))
                            .setResponseTimeout(
                                    Timeout.ofMilliseconds(
                                            Math.min(
                                                    documentProperties.getReadTimeoutMs(),
                                                    remainingMs)))
                            .build());
            // 3. 响应处理器只读取这一跳，Fetcher 继续统一控制跳转次数和目标验证。
            var fetchResult = httpClient.execute(
                    httpGetRequest,
                    new RemoteResourceResponseHandler(requestUri, maxBytes, deadlineNanos, documentProperties));
            if (fetchResult.redirect() == null) {
                return new DocumentInputResource(
                        source,
                        fetchResult.mime(),
                        fetchResult.bytes(),
                        ResourceSource.REMOTE);
            }
            targetUri = fetchResult.redirect();
        }
        throw new IOException("Too many redirects");
    }

    /** 仅允许 HTTP(S) 默认端口；URL 凭据与 fragment 不属于资源抓取协议。 */
    public static void validateUri(URI uri) throws IOException {
        if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                || uri.getHost() == null
                || uri.getUserInfo() != null
                || uri.getFragment() != null
                || !(uri.getPort() == UNSPECIFIED_PORT
                        || uri.getPort() == HTTP_PORT
                        || uri.getPort() == HTTPS_PORT)) {
            throw new IOException("Unsafe resource URI");
        }
    }

    /** 保留既有校验调用边界，实际 DNS 连接与此处共用同一套地址规则。 */
    public static boolean publicAddress(InetAddress address) {
        return PublicAddressDnsResolver.publicAddress(address);
    }

    @PreDestroy
    @Override
    public void close() throws IOException {
        httpClient.close();
    }
}
