# 接口限流

业务模块依赖 security starter 后，在 Spring 管理的 HTTP Controller 公共方法上加注解即可；不需要业务 Service 申请额度。自调用绕过 Spring 代理，不能作为受保护的 HTTP 入口使用。

```java
@RateLimit
@PostMapping("/session")
```

默认固定窗口：每个接口、每个客户端 IP 独立计数，300 秒最多 5 次。登录和注册均使用此默认值，两者互不消耗额度。它限制请求频率，不管理用户业务配额。

```java
@RateLimit(count = 10, windowSeconds = 60)
```

令牌桶：初始为满，允许容量范围内的突发，随后按每秒补充速率连续恢复额度。下面允许初始 8 次突发，每秒补充 2 个令牌。

```java
@RateLimit(strategy = RateLimitStrategy.TOKEN_BUCKET, capacity = 8, refillRate = 2)
```

| 策略 | 生效参数 | 默认值 |
| --- | --- | --- |
| FIXED_WINDOW | count、windowSeconds | 5、300 秒 |
| TOKEN_BUCKET | capacity、refillRate | 5、1 个/秒 |

不传 key 或 message：接口身份从目标方法生成，客户端地址使用容器的 remoteAddr，键主体统一摘要。反向代理部署需正确配置容器还原真实客户端地址，本功能不信任客户端直接提供的转发头。

执行链路：MVC 字段校验 → RateLimitAspect → RedisRateLimiter → Controller → Service。HTTPS、登录、角色和 CSRF 仍由原认证入口处理，限流不再复制这些检查。超限抛出统一 RATE_LIMITED，现有全局异常处理器返回 HTTP 429；Redis 故障不静默放行。

固定窗口首次请求开始计时，后续成功、失败或超限请求均不延长窗口；此算法允许窗口交界处突发。令牌桶使用 Redis 时间，原子执行补充和扣减；空闲到补满后可过期清理。两种策略在多实例间共享 Redis 状态。

旧 LoginThrottleService、FixedWindowRateLimiter 包装及 security 中的登录/注册频率配置已移除，避免新旧两套规则。新键采用策略、方法、IP 及限额参数隔离，部署切换时会开启新窗口；原计数键按旧 TTL 自行过期，不批量删除。

本次没有增加按账号配额、SpEL、自定义策略注册平台或 Chat 接口；令牌桶能力可由后续真实入口直接选用，无需复制 Service。
