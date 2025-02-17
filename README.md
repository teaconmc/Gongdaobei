# Gongdaobei

Gongdaobei（公道杯）：Velocity 分服服务发现 & 负载均衡。

> 茶海又称茶盅或公道杯。茶壶内之茶汤浸泡至适当浓度后，茶汤倒至茶海，再分倒于各小茶杯内，以求茶汤浓度之均匀。亦可于茶海上覆一滤网，以滤去茶渣、茶末。没有专用的茶海时，也可以用茶壶充当。其大致功用为：盛放泡好之茶汤，再分倒各杯，使各杯茶汤浓度相若，沉淀茶渣。
> 
> ——百度百科（<https://baike.baidu.com/item/%E8%8C%B6%E5%85%B7/631>）

## 配置文件

NeoForge 侧配置文件位于 `config/gongdaobei.toml`，Velocity 侧位于 `plugins/gongdaobei/gongdaobei.toml`。

```toml
################################
# common 块配置为 NeoForge / Velocity 共用
################################
[common]
# 服务发现的 redis 地址，支持 sentinel 自动切换
# 支持环境变量解析，可通过「:-」分隔符添加默认值
discoveryRedisUri = "${GONGDAOBEI_SERVICE_DISCOVERY:-redis://localhost:6379/0}"
################################
# service 块配置为 NeoForge 独有
################################
[service]
# 用于 Velocity 层连接 NeoForge 层的内部地址
# 如未指定端口，则以 server.properties 里的端口为准
# 支持环境变量解析，可通过「:-」分隔符添加默认值
internalAddress = "${GONGDAOBEI_SERVICE_INTERNAL:-localhost}"
# 外部地址，用于玩家通过特定域名连接服务器时识别
# 如未指定端口，则以玩家连接 Velocity 侧使用的端口为准
# 支持环境变量解析，可通过「:-」分隔符添加默认值
externalAddresses = []
# 是否为 fallback 服务器
# 如任何服务器外部地址均不满足玩家连接，玩家将选择一 fallback 服务器连接
isfallbackServer = true
# 是否将玩家数据与 redis 同步
# 当玩家因为负载均衡分配到不同服务器时将分享相同的玩家数据
syncPlayersFromRedis = true
# 版本号，需为合法的语义化版本，可带 v 前缀
# 版本新旧决定服务器优先级（版本比较服从语义化版本定义）
# 玩家只会连接优先级最高即版本号最新的服务器
# 可为空字符串，若为空字符串则为无版本号服务器，优先级最低
# 支持环境变量解析，可通过「:-」分隔符添加默认值
# 如以下配置支持通过设置 GONGDAOBEI_SERVICE_VERSION 环境变量指定版本号
version = "${GONGDAOBEI_SERVICE_VERSION:-1.0.0}"
# 亲和有效时间，单位毫秒
# 玩家在亲和有效时间内的重连时，将分配到上一个连接的服务器
affinityMillis = 1200000
################################
# velocity 块配置为 Velocity 独有
################################
[velocity]
# Velocity 侧开启的 Prometheus Metrics 服务器端口
# 应当为 0 和 65535 之间，如果为 0 则不开启服务器
prometheusServerPort = 0
# 外部地址白名单，位于白名单的外部地址
# 将永远出现在 Prometheus Metrics 服务中
# 同时将永远不会使玩家连接到 fallback 服务器
externalAddressWhitelist = []
```

## Prometheus Metrics 服务器

Prometheus Metrics 服务器会在 Velocity 侧开放，并监听 `prometheusServerPort` 设置的端口。

以下是 Prometheus Metrics 服务器提供的指标：

| 指标类型    | 指标名                                                                                  | 解释                                                                                                                  |
|---------|--------------------------------------------------------------------------------------|---------------------------------------------------------------------------------------------------------------------|
| Counter | `gongdaobei_pings_total`                                                             | 服务器在 Velocity 侧总共 ping 了多少次                                                                                         |
| Counter | `gongdaobei_logins_total`                                                            | 服务器在 Velocity 侧总共有多少次有效登录                                                                                           |
| Counter | `gongdaobei_logins_with_affinity_total`                                              | 服务器在 Velocity 侧总共有多少次在亲和有效时间内的有效登录                                                                                  |
| Counter | `gongdaobei_player_network_bytes_total{channel}`                                     | 服务器在 Velocity 侧特定 `channel`（有效值：`incoming` 和 `outgoing`）共接收或发送了多少字节的来自玩家侧的数据                                        |
| Counter | `gongdaobei_service_network_bytes_total{channel,name}`                               | 服务器名称标记为 `name` 的端特定 `channel`（有效值：`incoming` 和 `outgoing`）共接收或发送了多少字节的来自 Velocity 侧的数据                             |
| Counter | `gongdaobei_fallback_service_network_bytes_total{channel,name}`                      | 服务器名称标记为 `name` 且标记为 fallback 的端特定 `channel`（有效值：`incoming` 和 `outgoing`）共接收或发送了多少字节的来自 Velocity 侧的数据               |
| Counter | `gongdaobei_targeted_service_network_bytes_total{channel,address,name}`              | 服务器名称标记为 `name` 且标记 `address` 外部域名的端特定 `channel`（有效值：`incoming` 和 `outgoing`）共接收或发送了多少字节的来自 Velocity 侧的数据           |
| Counter | `gongdaobei_player_uncompressed_network_bytes_total{channel}`                        | 同 `gongdaobei_player_network_bytes_total`，但统计的是解压后的字节数（如果服务端设置了 `network-compression-threshold` 则两者会有所不同）           |
| Counter | `gongdaobei_service_uncompressed_network_bytes_total{channel,name}`                  | 同 `gongdaobei_service_network_bytes_total`，但统计的是解压后的字节数（如果服务端设置了 `network-compression-threshold` 则两者会有所不同）          |
| Counter | `gongdaobei_fallback_service_uncompressed_network_bytes_total{channel,name}`         | 同 `gongdaobei_fallback_service_network_bytes_total`，但统计的是解压后的字节数（如果服务端设置了 `network-compression-threshold` 则两者会有所不同） |
| Counter | `gongdaobei_targeted_service_uncompressed_network_bytes_total{channel,address,name}` | 同 `gongdaobei_targeted_service_network_bytes_total`，但统计的是解压后的字节数（如果服务端设置了 `network-compression-threshold` 则两者会有所不同） |
| Gauge   | `gongdaobei_online_players`                                                          | 服务器总共多少在线玩家                                                                                                         |
| Gauge   | `gongdaobei_fallback_online_players`                                                 | 服务器标记为 fallback 的端总共多少在线玩家                                                                                          |
| Gauge   | `gongdaobei_targeted_online_players{address}`                                        | 服务器标记 `address` 外部域名的端总共多少在线玩家                                                                                      |
| Gauge   | `gongdaobei_maximum_players`                                                         | 服务器玩家上限的值求和                                                                                                         |
| Gauge   | `gongdaobei_fallback_maximum_players`                                                | 服务器标记为 fallback 的端玩家上限的值求和                                                                                          |
| Gauge   | `gongdaobei_targeted_maximum_players{address}`                                       | 服务器标记 `address` 外部域名的端玩家上限的值求和                                                                                      |
| Gauge   | `gongdaobei_service_instances`                                                       | 服务器总共注册了多少端                                                                                                         |
| Gauge   | `gongdaobei_fallback_service_instances`                                              | 服务器总共注册了多少标记为 fallback 的端                                                                                           |
| Gauge   | `gongdaobei_targeted_service_instances{address}`                                     | 服务器总共注册了多少标记 `address` 外部域名的端                                                                                       |
| Gauge   | `gongdaobei_latest_fallback_service_instances`                                       | 服务器总共注册了多少标记为 fallback 且为最新版本的端                                                                                     |
| Gauge   | `gongdaobei_latest_targeted_service_instances{address}`                              | 服务器总共注册了多少标记 `address` 外部域名且为最新版本的端                                                                                 |
| Gauge   | `gongdaobei_service_tick_duration_seconds{name}`                                     | 服务器中名称标记为 `name` 的端每 tick 所占用的秒数                                                                                    |
| Gauge   | `gongdaobei_fallback_service_tick_duration_seconds{name}`                            | 服务器中名称标记为 `name` 且标记为 fallback 的端每 tick 所占用的秒数                                                                      |
| Gauge   | `gongdaobei_targeted_service_tick_duration_seconds{address,name}`                    | 服务器中名称标记为 `name` 且标记 `address` 外部域名的端每 tick 所占用的秒数                                                                  |
