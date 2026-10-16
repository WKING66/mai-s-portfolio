package dev.amai.portfolio.blog.component;

import java.net.InetAddress;
import java.net.UnknownHostException;
import org.apache.hc.client5.http.DnsResolver;

/** 在实际连接的 DNS 解析阶段检查全部地址；混入任一非公网结果即拒绝，不缓存绕过校验。 */
final class PublicAddressDnsResolver implements DnsResolver {
    private static final int IPV4_ADDRESS_BYTES = 4;
    private static final int UNSIGNED_BYTE_MASK = 0xff;

    @Override
    public InetAddress[] resolve(String host) throws UnknownHostException {
        InetAddress[] addresses = InetAddress.getAllByName(host);
        for (var address : addresses) {
            if (!publicAddress(address)) {
                throw new UnknownHostException("Non-public destination");
            }
        }
        return addresses;
    }

    @Override
    public String resolveCanonicalHostname(String host) throws UnknownHostException {
        return host;
    }

    /** 当前采用保守公网筛选；数值为 IP 前缀/位掩码，不是文档类型或业务状态编码。 */
    static boolean publicAddress(InetAddress address) {
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
}
