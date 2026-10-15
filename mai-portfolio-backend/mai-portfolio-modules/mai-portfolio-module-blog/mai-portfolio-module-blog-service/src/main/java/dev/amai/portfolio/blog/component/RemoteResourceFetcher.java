package dev.amai.portfolio.blog.component;

import static dev.amai.portfolio.blog.constant.DocumentPolicy.IO_BUFFER_BYTES;
import static org.springframework.http.MediaType.IMAGE_JPEG_VALUE;
import static org.springframework.http.MediaType.IMAGE_PNG_VALUE;

import dev.amai.portfolio.blog.config.DocumentProperties;
import dev.amai.portfolio.blog.entity.domain.DocumentInputResource;
import dev.amai.portfolio.blog.enums.ResourceSource;
import jakarta.annotation.PreDestroy;
import java.io.*;
import java.net.*;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import org.apache.hc.client5.http.DnsResolver;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.*;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.http.HttpHeaders;
import org.apache.hc.core5.http.HttpStatus;
import org.apache.hc.core5.util.Timeout;
import org.springframework.stereotype.Component;

/** DNS 校验发生在实际建立连接的解析器内，重定向逐跳校验；不继承 Cookie/凭据/系统代理。 */
@Component
public class RemoteResourceFetcher implements AutoCloseable {
    private static final int IPV4_ADDRESS_BYTES = 4;
    private static final int UNSIGNED_BYTE_MASK = 0xff;
    private static final int UNSPECIFIED_PORT = -1, HTTP_PORT = 80, HTTPS_PORT = 443;
    private final DocumentProperties documentProperties;
    private final CloseableHttpClient httpClient;

    public RemoteResourceFetcher(DocumentProperties documentProperties) {
        this.documentProperties = documentProperties;
        DnsResolver dnsResolver =
                new DnsResolver() {
                    @Override
                    public InetAddress[] resolve(String host) throws UnknownHostException {
                        InetAddress[] addresses = InetAddress.getAllByName(host);
                        // 实际连接时检查每个解析结果；混入任一非公网地址便拒绝整次解析。
                        for (var address : addresses) {
                            if (!publicAddress(address)) {
                                throw new UnknownHostException("Non-public destination");
                            }
                        }
                        return addresses;
                    }

                    @Override
                    public String resolveCanonicalHostname(String host)
                            throws UnknownHostException {
                        return host;
                    }
                };
        httpClient =
                HttpClients.custom()
                        .setConnectionManager(
                                PoolingHttpClientConnectionManagerBuilder.create()
                                        .setDnsResolver(dnsResolver)
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
            Fetch fetchResult =
                    httpClient.execute(
                            httpGetRequest,
                            httpResponse -> {
                                int statusCode = httpResponse.getCode();
                                if (statusCode >= HttpStatus.SC_MULTIPLE_CHOICES
                                        && statusCode < HttpStatus.SC_BAD_REQUEST) {
                                    var location =
                                            httpResponse.getFirstHeader(HttpHeaders.LOCATION);
                                    if (location == null) {
                                        throw new IOException("Missing redirect location");
                                    }
                                    try {
                                        return new Fetch(
                                                requestUri.resolve(location.getValue()),
                                                null,
                                                null);
                                    } catch (IllegalArgumentException failure) {
                                        throw new IOException("Invalid redirect URI", failure);
                                    }
                                }
                                if (statusCode != HttpStatus.SC_OK
                                        || httpResponse.getEntity() == null) {
                                    throw new IOException("Resource fetch failed");
                                }
                                var httpEntity = httpResponse.getEntity();
                                if (httpEntity.getContentLength() > maxBytes) {
                                    throw new IOException("Resource too large");
                                }
                                // 3. 同时检查声明长度、实际累计长度与读取期限，完成后交由 Asset 解码。
                                byte[] bytes;
                                long readDeadlineNanos =
                                        Math.min(
                                                deadlineNanos,
                                                System.nanoTime()
                                                        + TimeUnit.MILLISECONDS
                                                                .toNanos(
                                                                        documentProperties
                                                                                .getReadTimeoutMs()));
                                try (var input = httpEntity.getContent();
                                        var output = new ByteArrayOutputStream()) {
                                    byte[] buffer = new byte[IO_BUFFER_BYTES];
                                    int bytesRead;
                                    while ((bytesRead = input.read(buffer)) != -1) {
                                        if (System.nanoTime() > readDeadlineNanos
                                                || output.size() + bytesRead > maxBytes) {
                                            throw new IOException("Resource read limit exceeded");
                                        }
                                        output.write(buffer, 0, bytesRead);
                                    }
                                    bytes = output.toByteArray();
                                }
                                if (bytes.length == 0 || bytes.length > maxBytes) {
                                    throw new IOException("Resource size invalid");
                                }
                                String mime = httpEntity.getContentType();
                                if (mime != null) {
                                    mime = mime.split(";", 2)[0].strip().toLowerCase(Locale.ROOT);
                                }
                                if (!IMAGE_PNG_VALUE.equals(mime)
                                        && !IMAGE_JPEG_VALUE.equals(mime)) {
                                    throw new IOException("Unsupported image type");
                                }
                                return new Fetch(null, mime, bytes);
                            });
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

    /** 当前采用保守公网筛选；数值为 IP 前缀/位掩码，不是文档类型或业务状态编码。 */
    public static boolean publicAddress(InetAddress address) {
        if (address.isAnyLocalAddress()
                || address.isLoopbackAddress()
                || address.isLinkLocalAddress()
                || address.isSiteLocalAddress()
                || address.isMulticastAddress()) {
            return false;
        }
        byte[] addressBytes = address.getAddress();
        if (addressBytes.length == IPV4_ADDRESS_BYTES) {
            // 转成无符号 IPv4 字节；额外屏蔽 CGNAT、基准测试及含保留地址的前缀段。
            // 192.0、198.51、203.0 等按前两字节保守排除，范围比对应文档示例网段更宽。
            int firstOctet = addressBytes[0] & UNSIGNED_BYTE_MASK,
                    secondOctet = addressBytes[1] & UNSIGNED_BYTE_MASK;
            return !(firstOctet == 0
                    || firstOctet >= 224
                    || (firstOctet == 100 && secondOctet >= 64 && secondOctet <= 127)
                    || (firstOctet == 192 && (secondOctet == 0 || secondOctet == 2))
                    || (firstOctet == 198
                            && (secondOctet == 18 || secondOctet == 19 || secondOctet == 51))
                    || (firstOctet == 203 && secondOctet == 0));
        }
        // 仅接受 2000::/3 全局单播；排除 2002::/16、2001:db8::/32 和 2001:00xx 段。
        return (addressBytes[0] & 0xe0) == 0x20
                && !((addressBytes[0] & UNSIGNED_BYTE_MASK) == 0x20
                        && (addressBytes[1] & UNSIGNED_BYTE_MASK) == 0x02)
                && !((addressBytes[0] & UNSIGNED_BYTE_MASK) == 0x20
                        && (addressBytes[1] & UNSIGNED_BYTE_MASK) == 0x01
                        && ((addressBytes[2] & UNSIGNED_BYTE_MASK) == 0x0d
                                        && (addressBytes[3] & UNSIGNED_BYTE_MASK) == 0xb8
                                || addressBytes[2] == 0));
    }

    @PreDestroy
    @Override
    public void close() throws IOException {
        httpClient.close();
    }

    private record Fetch(
            URI redirect,

            String mime,

            byte[] bytes) {}
}
