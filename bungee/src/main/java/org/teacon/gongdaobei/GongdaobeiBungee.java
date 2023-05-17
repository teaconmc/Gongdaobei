package org.teacon.gongdaobei;

import com.google.common.base.Preconditions;
import com.google.common.net.HostAndPort;
import com.vdurmont.semver4j.Semver;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.sync.RedisCommands;
import io.lettuce.core.codec.StringCodec;
import io.lettuce.core.masterreplica.MasterReplica;
import net.md_5.bungee.api.ProxyServer;
import net.md_5.bungee.api.ServerPing;
import net.md_5.bungee.api.chat.TextComponent;
import net.md_5.bungee.api.config.ServerInfo;
import net.md_5.bungee.api.event.ProxyPingEvent;
import net.md_5.bungee.api.event.ServerConnectEvent;
import net.md_5.bungee.api.event.ServerDisconnectEvent;
import net.md_5.bungee.api.plugin.Listener;
import net.md_5.bungee.api.plugin.Plugin;
import net.md_5.bungee.api.scheduler.ScheduledTask;
import net.md_5.bungee.event.EventHandler;

import java.io.Closeable;
import java.net.InetSocketAddress;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;
import java.util.stream.IntStream;

public final class GongdaobeiBungee extends Plugin {
    private GongdaobeiTomlConfig.Common config;
    private Handler handler;

    @Override
    public void onEnable() {
        Preconditions.checkArgument(this.getDataFolder().isDirectory() || this.getDataFolder().mkdirs());
        var file = this.getDataFolder().toPath().resolve("gongdaobei.toml");
        this.getLogger().info("Loading from the configuration file ...");
        this.config = GongdaobeiTomlConfig.Common.load(file).save(file);
        this.getLogger().info("- Discovery Redis URI: " + this.config.discoveryRedisUri().toURI());
        this.handler = new Handler(this, this.config);
    }

    @Override
    public void onDisable() {
        Preconditions.checkArgument(this.getDataFolder().isDirectory() || this.getDataFolder().mkdirs());
        var file = this.getDataFolder().toPath().resolve("gongdaobei.toml");
        this.getLogger().info("Saving to the configuration file ...");
        this.config.save(file);
        this.handler.close();
    }

    public static final class Handler implements Runnable, Listener, Closeable {
        private final Logger logger;
        private final ProxyServer server;
        private final RedisClient redisClient;
        private final ScheduledTask scheduledTask;
        private final RedisCommands<String, String> commands;
        private final ConcurrentMap<HostAndPort, ServerInfo> cachedServerInfoMap = new ConcurrentHashMap<>();
        private final AtomicReference<Map<HostAndPort, GongdaobeiServiceParams>> serviceParams = new AtomicReference<>(Map.of());

        public Handler(Plugin plugin, GongdaobeiTomlConfig.Common config) {
            this.logger = plugin.getLogger();
            this.server = plugin.getProxy();
            this.redisClient = RedisClient.create();
            this.redisClient.setOptions(GongdaobeiUtil.getRedisClientOptions());
            this.commands = MasterReplica.connect(this.redisClient, StringCodec.UTF8, config.discoveryRedisUri()).sync();
            this.scheduledTask = this.server.getScheduler().schedule(plugin, this, 2500, 2500, TimeUnit.MILLISECONDS);
            this.server.getPluginManager().registerListener(plugin, this);
        }

        @Override
        public void run() {
            var services = GongdaobeiUtil.getServiceParams(this.commands);
            var retiredServices = new LinkedHashSet<HostAndPort>();
            var newServiceParams = new LinkedHashMap<HostAndPort, GongdaobeiServiceParams>(services.size());
            for (var entry : services.entrySet()) {
                var params = entry.getValue();
                if (params.isRetired) {
                    retiredServices.add(entry.getKey());
                    continue;
                }
                var socket = InetSocketAddress.createUnresolved(entry.getKey().getHost(), entry.getKey().getPort());
                this.cachedServerInfoMap.computeIfAbsent(entry.getKey(), k -> {
                    var serverName = "gongdaobei:" + entry.getKey();
                    return this.server.constructServerInfo(serverName, socket, params.motd, false);
                });
                newServiceParams.put(entry.getKey(), params);
            }
            var oldServiceParams = this.serviceParams.getAndSet(Map.copyOf(newServiceParams));
            var missingServices = new LinkedHashSet<HostAndPort>();
            var joiningServices = newServiceParams.keySet();
            for (var oldAddr : oldServiceParams.keySet()) {
                var retired = retiredServices.contains(oldAddr);
                var joining = joiningServices.remove(oldAddr);
                if (!retired && !joining) {
                    missingServices.add(oldAddr);
                }
            }
            if (missingServices.size() > 0) {
                this.logger.warning("Registered service status changed (retired: " +
                        retiredServices + ", joining: " + joiningServices + ", missing: " + missingServices + ")");
            } else if (retiredServices.size() + joiningServices.size() > 0) {
                this.logger.info("Registered service status changed (retired: " +
                        retiredServices + ", joining: " + joiningServices + ", missing: " + missingServices + ")");
            }
        }

        @EventHandler
        public void on(ServerDisconnectEvent event) {
            GongdaobeiUtil.getHostAndPort(event.getTarget().getName(), "gongdaobei:").ifPresent(addr -> {
                var serviceParams = this.serviceParams.get();
                if (serviceParams.containsKey(addr)) {
                    var affinityMillis = serviceParams.get(addr).affinityMillis;
                    GongdaobeiUtil.setAffinityTarget(event.getPlayer().getUniqueId(), addr, this.commands, affinityMillis);
                }
            });
        }

        @EventHandler
        public void on(ServerConnectEvent event) {
            var player = event.getPlayer();
            if (player != null && event.getReason() == ServerConnectEvent.Reason.JOIN_PROXY) {
                // collect choices which have the latest versions
                var latestTargetVersion = Optional.<Semver>empty();
                var latestFallbackVersion = Optional.<Semver>empty();
                var targetChoicesByInternalAddr = new LinkedHashMap<HostAndPort, GongdaobeiServiceParams>();
                var fallbackChoicesByInternalAddr = new LinkedHashMap<HostAndPort, GongdaobeiServiceParams>();
                var playerExternalAddr = player.getPendingConnection().getVirtualHost();
                for (var entry : this.serviceParams.get().entrySet()) {
                    var params = entry.getValue();
                    var isTarget = params.externalAddresses.stream().map(a -> InetSocketAddress
                            .createUnresolved(a.getHost(), a.getPort())).anyMatch(playerExternalAddr::equals);
                    // noinspection DuplicatedCode
                    if (isTarget) {
                        if (params.version.isPresent() && (latestTargetVersion.isEmpty()
                                || params.version.get().isGreaterThan(latestTargetVersion.get()))) {
                            latestTargetVersion = params.version;
                            targetChoicesByInternalAddr.clear();
                        }
                        if (params.version.isEmpty() ? latestTargetVersion.isEmpty()
                                : latestTargetVersion.filter(params.version.get()::isEquivalentTo).isPresent()) {
                            targetChoicesByInternalAddr.put(entry.getKey(), params);
                        }
                    }
                    var isFallback = params.isFallback;
                    // noinspection DuplicatedCode
                    if (isFallback) {
                        if (params.version.isPresent() && (latestFallbackVersion.isEmpty()
                                || params.version.get().isGreaterThan(latestFallbackVersion.get()))) {
                            latestFallbackVersion = params.version;
                            fallbackChoicesByInternalAddr.clear();
                        }
                        if (params.version.isEmpty() ? latestFallbackVersion.isEmpty()
                                : latestFallbackVersion.filter(params.version.get()::isEquivalentTo).isPresent()) {
                            fallbackChoicesByInternalAddr.put(entry.getKey(), params);
                        }
                    }
                }
                // if there is an affinity host which has space, send the player to that server
                var playerUniqueId = player.getUniqueId();
                var affinityHost = GongdaobeiUtil.getAffinityTarget(playerUniqueId, this.commands);
                var affinityParams = affinityHost
                        .map(targetChoicesByInternalAddr::get)
                        .or(() -> affinityHost.map(fallbackChoicesByInternalAddr::get));
                if (affinityParams.isPresent()) {
                    var online = affinityParams.get().onlinePlayers;
                    var maximum = affinityParams.get().maximumPlayers;
                    if (online < maximum) {
                        this.logger.info("Affinity server found, send the player to " + affinityHost.get());
                        event.setTarget(this.cachedServerInfoMap.get(affinityHost.get()));
                        return;
                    }
                }
                // weighted random choices
                for (var choices : List.of(targetChoicesByInternalAddr, fallbackChoicesByInternalAddr)) {
                    var online = choices.values().stream().mapToInt(p -> p.onlinePlayers).toArray();
                    var maximum = choices.values().stream().mapToInt(p -> p.maximumPlayers).toArray();
                    var maxSpaceRatio = IntStream.range(0, choices.size()).mapToDouble(i ->
                            online[i] < maximum[i] ? (online[i] + 1.0) / maximum[i] : 1.0).max().orElse(0.0);
                    var weights = IntStream.range(0, choices.size()).mapToDouble(i ->
                            Math.max(0.0, maximum[i] * maxSpaceRatio - online[i])).toArray();
                    var random = Math.random() * Arrays.stream(weights).sum();
                    var iterator = choices.keySet().iterator();
                    for (var i = 0; i < choices.size(); ++i) {
                        var next = iterator.next();
                        random -= weights[i];
                        if (random < 0.0) {
                            this.logger.info("Load balancing performed, send the player to " + next);
                            event.setTarget(this.cachedServerInfoMap.get(next));
                            return;
                        }
                    }
                }
                // if there is no choice (such that all the choices are full), disconnect
                player.disconnect(TextComponent.fromLegacyText(this.server.getTranslation("proxy_full")));
                this.logger.warning("No choice found, throw the player outside");
                event.setCancelled(true);
            }
        }

        @EventHandler
        public void on(ProxyPingEvent event) {
            var serviceParams = this.serviceParams.get();
            var limit = this.server.getConfig().getPlayerLimit();
            var online = serviceParams.values().stream().mapToInt(p -> p.onlinePlayers).sum();
            var maximum = serviceParams.values().stream().mapToInt(p -> p.maximumPlayers).sum();
            event.getResponse().setPlayers(new ServerPing.Players(
                    limit > 0 ? Math.min(limit, maximum) : maximum, online, new ServerPing.PlayerInfo[0]));
        }

        @Override
        public void close() {
            this.server.getPluginManager().unregisterListener(this);
            this.scheduledTask.cancel();
            this.redisClient.close();
        }
    }
}
