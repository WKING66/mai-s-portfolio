import asyncio
from ipaddress import ip_address
from socket import AF_INET, SOCK_STREAM, gaierror

from httpx import (
    URL,
    AsyncClient,
    AsyncHTTPTransport,
    ConnectError,
    InvalidURL,
    Limits,
    Request,
    Response,
)


def validate_provider_url(value: str) -> URL:
    try:
        url = URL(value)
    except InvalidURL as error:
        raise ValueError("Invalid provider URL.") from error
    host = url.raw_host.decode("ascii")
    if (
        url.scheme != "https"
        or not host
        or "." not in host
        or host.endswith(".")
        or url.port not in (None, 443)
        or url.userinfo
        or url.query
        or url.fragment
    ):
        raise ValueError(
            "Provider URL must be a public HTTPS base URL without credentials."
        )
    try:
        ip_address(host)
    except ValueError:
        return url
    raise ValueError("Provider URL requires a DNS hostname.")


class ProviderTransport(AsyncHTTPTransport):
    async def handle_async_request(self, request: Request) -> Response:
        url = request.url
        try:
            hostname = validate_provider_url(str(url)).raw_host.decode("ascii")
            async with asyncio.timeout(3):
                addresses = await asyncio.get_running_loop().getaddrinfo(
                    hostname, 443, family=AF_INET, type=SOCK_STREAM
                )
        except (ValueError, gaierror, TimeoutError) as error:
            raise ConnectError(
                "Model endpoint is not available.", request=request
            ) from error
        targets = [entry[4][0] for entry in addresses]
        if not targets or any(
            not ip_address(address).is_global or ip_address(address).is_multicast
            for address in targets
        ):
            raise ConnectError(
                "Model endpoint must resolve to public IPv4.", request=request
            )

        # 连接已核验的 IP，避免建立连接时再次解析到内网。证书和 Host 仍校验供应商域名。
        request.url = url.copy_with(host=targets[0])
        request.headers["Host"] = hostname
        request.extensions["sni_hostname"] = hostname
        try:
            return await super().handle_async_request(request)
        finally:
            request.url = url


def provider_http_client() -> AsyncClient:
    # 禁止跳转和环境代理。关闭连接复用，避免不同供应商共用 IP 时误用上一域名的 TLS 会话。
    return AsyncClient(
        transport=ProviderTransport(
            limits=Limits(max_connections=16, max_keepalive_connections=0)
        ),
        follow_redirects=False,
        trust_env=False,
    )
