from ipaddress import ip_address

from httpx import URL, AsyncClient, InvalidURL, Limits


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
            "Provider URL must be an HTTPS base URL without credentials."
        )
    try:
        ip_address(host)
    except ValueError:
        return url
    raise ValueError("Provider URL requires a DNS hostname.")


def provider_http_client() -> AsyncClient:
    # 由 HTTPX 使用供应商域名建立 TLS，让系统网络处理 DNS 和连接。
    return AsyncClient(
        limits=Limits(max_connections=16, max_keepalive_connections=0),
        follow_redirects=False,
        trust_env=False,
    )
