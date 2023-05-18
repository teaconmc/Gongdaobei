# Gongdaobei

Gongdaobei（公道杯）：BungeeCord 分服服务发现 & 负载均衡。

> 茶海又称茶盅或公道杯。茶壶内之茶汤浸泡至适当浓度后，茶汤倒至茶海，再分倒于各小茶杯内，以求茶汤浓度之均匀。亦可于茶海上覆一滤网，以滤去茶渣、茶末。没有专用的茶海时，也可以用茶壶充当。其大致功用为：盛放泡好之茶汤，再分倒各杯，使各杯茶汤浓度相若，沉淀茶渣。
> 
> ——百度百科（<https://baike.baidu.com/item/%E8%8C%B6%E5%85%B7/631>）

## 配置文件

Forge 侧配置文件位于 `config/gongdaobei.toml`，Bungee 侧位于 `config/Gongdaobei/gongdaobei.toml`。

```toml
################################
# common 块配置为 forge / bungee 共用
################################
[common]
# 服务发现的 redis 地址，支持 sentinel 自动切换
discoveryRedisUri = "redis://localhost"
################################
# service 块配置为 forge 独有
################################
[service]
# 用于 bungee 层连接 forge 层的内部地址
# 如未指定端口，则以 server.properties 里的端口为准
internalAddress = "localhost"
# 外部地址，用于玩家通过特定域名连接服务器时识别
# 如未指定端口，则以玩家连接 bungee 侧使用的端口为准
externalAddresses = []
# 是否为 fallback 服务器
# 如任何服务器外部地址均不满足玩家连接，玩家将选择一 fallback 服务器连接
isFallbackServer = true
# 版本号，需为合法的语义化版本，可带 v 前缀
# 版本新旧决定服务器优先级（版本比较服从语义化版本定义）
# 玩家只会连接优先级最高即版本号最新的服务器
# 可为空字符串，若为空字符串则为无版本号服务器，优先级最低
# 支持 Java System Properties 解析，可通过「:-」分隔符添加默认值
# 如以下配置支持通过 -Dgongdaobei.service.version=x.y.z 参数指定版本号
version = "${gongdaobei.service.version:-1.0.0}"
# 亲和有效时间，单位毫秒
# 玩家在亲和有效时间内的重连时，将分配到上一个连接的服务器
affinityMillis = 1200000
```
