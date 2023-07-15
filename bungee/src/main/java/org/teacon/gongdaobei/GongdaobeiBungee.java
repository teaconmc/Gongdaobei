/*
 * Copyright (C) 2023 TeaConMC <contact@teacon.org>
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */
package org.teacon.gongdaobei;

import com.google.common.base.Preconditions;
import com.google.common.net.HostAndPort;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.codec.StringCodec;
import io.lettuce.core.masterreplica.MasterReplica;
import net.md_5.bungee.api.ProxyServer;
import net.md_5.bungee.api.ServerPing;
import net.md_5.bungee.api.chat.TextComponent;
import net.md_5.bungee.api.config.ServerInfo;
import net.md_5.bungee.api.connection.PendingConnection;
import net.md_5.bungee.api.event.ProxyPingEvent;
import net.md_5.bungee.api.event.ServerConnectEvent;
import net.md_5.bungee.api.event.ServerDisconnectEvent;
import net.md_5.bungee.api.plugin.Listener;
import net.md_5.bungee.api.plugin.Plugin;
import net.md_5.bungee.api.scheduler.ScheduledTask;
import net.md_5.bungee.event.EventHandler;

import javax.annotation.Nullable;
import java.io.Closeable;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.net.InetSocketAddress;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
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
        this.getLogger().info("- Discovery Redis URI: " + this.config.discoveryRedisUri().getKey());
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


    public record ServerEntry(boolean isTarget,
                              boolean isFallback,
                              HostAndPort internalAddress,
                              GongdaobeiServiceParams serviceParams) {
        public static List<ServerEntry> from(PendingConnection connection,
                                             Map<HostAndPort, GongdaobeiServiceParams> serviceParams) {
            var playerExternalAddr = connection.getVirtualHost();
            var targetChoicesByInternal = new HashSet<HostAndPort>();
            var fallbackChoicesByInternal = new HashSet<HostAndPort>();
            var latestTargetVersion = new GongdaobeiTomlConfig.VersionPattern();
            var latestFallbackVersion = new GongdaobeiTomlConfig.VersionPattern();
            for (var entry : serviceParams.entrySet()) {
                var params = entry.getValue();
                var isTarget = !params.isRetired && params.externalAddresses.stream()
                        .filter(addr -> addr.getHost().equals(playerExternalAddr.getHostString()))
                        .anyMatch(addr -> !addr.hasPort() || addr.getPort() == playerExternalAddr.getPort());
                if (isTarget) {
                    if (params.version.compareTo(latestTargetVersion) > 0) {
                        latestTargetVersion = params.version;
                        targetChoicesByInternal.clear();
                    }
                    if (params.version.compareTo(latestTargetVersion) == 0) {
                        targetChoicesByInternal.add(entry.getKey());
                    }
                }
                var isFallback = !params.isRetired && params.isFallback;
                if (isFallback) {
                    if (params.version.compareTo(latestFallbackVersion) > 0) {
                        latestFallbackVersion = params.version;
                        fallbackChoicesByInternal.clear();
                    }
                    if (params.version.compareTo(latestFallbackVersion) == 0) {
                        fallbackChoicesByInternal.add(entry.getKey());
                    }
                }
            }
            var hasTargetServer = !targetChoicesByInternal.isEmpty();
            var result = new ArrayList<ServerEntry>(serviceParams.size());
            for (var entry : serviceParams.entrySet()) {
                var isTargetServer = targetChoicesByInternal.contains(entry.getKey());
                var isFallbackServer = fallbackChoicesByInternal.contains(entry.getKey());
                if (hasTargetServer ? isTargetServer : isFallbackServer) {
                    result.add(new ServerEntry(isTargetServer, isFallbackServer, entry.getKey(), entry.getValue()));
                }
            }
            return List.copyOf(result);
        }

        @Override
        public String toString() {
            return this.internalAddress +
                    " (target: " + (this.isTarget ? "TRUE" : "FALSE") +
                    ", fallback: " + (this.isFallback ? "TRUE" : "FALSE") + ")";
        }
    }

    public static final class Handler implements Runnable, Listener, Closeable {
        private static final @Nullable MethodHandle SET_FORGE_DATA;

        static {
            var methodSerForgeData = (MethodHandle) null;
            try {
                // noinspection JavaReflectionMemberAccess
                var field = ServerPing.class.getDeclaredField("forgeData");
                field.setAccessible(true);
                var lookup = MethodHandles.lookup();
                methodSerForgeData = lookup.unreflectSetter(field);
            } catch (NoSuchFieldException | IllegalAccessException e) {
                // do nothing here
            }
            SET_FORGE_DATA = methodSerForgeData;
        }

        private final Logger logger;
        private final ProxyServer server;
        private final RedisClient redisClient;
        private final ScheduledTask scheduledTask;
        private final Random randomGenerator = new Random();
        private final CompletableFuture<? extends StatefulRedisConnection<String, String>> conn;
        private final ConcurrentMap<HostAndPort, ServerInfo> cachedServerInfoMap = new ConcurrentHashMap<>();
        private final AtomicReference<Map<HostAndPort, GongdaobeiServiceParams>> serviceParams = new AtomicReference<>(Map.of());

        public Handler(Plugin plugin, GongdaobeiTomlConfig.Common config) {
            this.logger = plugin.getLogger();
            this.server = plugin.getProxy();
            this.redisClient = RedisClient.create();
            this.redisClient.setOptions(GongdaobeiUtil.getRedisClientOptions());
            this.conn = MasterReplica.connectAsync(
                    this.redisClient, StringCodec.UTF8, config.discoveryRedisUri().getValue()).whenComplete((c, e) -> {
                var uri = config.discoveryRedisUri().getValue().toURI();
                if (c != null) {
                    this.logger.info("Connected to the discovery redis server (" + uri + ")");
                }
                if (e != null) {
                    this.logger.log(Level.SEVERE, "Failed to connect to the discovery redis server (" +
                            uri + "), the server will run on offline mode and will not handle anything", e);
                }
            });
            this.scheduledTask = this.server.getScheduler().schedule(plugin, this, 2500, 2500, TimeUnit.MILLISECONDS);
            this.server.getPluginManager().registerListener(plugin, this);
        }

        @Override
        public void run() {
            var retiredServices = new LinkedHashSet<HostAndPort>();
            var joiningServices = new LinkedHashSet<HostAndPort>();
            var missingServices = new LinkedHashSet<HostAndPort>();
            var services = GongdaobeiUtil.getServiceParams(this.conn);
            var newServiceParams = new LinkedHashMap<HostAndPort, GongdaobeiServiceParams>(services.size());
            for (var entry : services.entrySet()) {
                var params = entry.getValue();
                var socket = InetSocketAddress.createUnresolved(entry.getKey().getHost(), entry.getKey().getPort());
                this.cachedServerInfoMap.computeIfAbsent(entry.getKey(), k -> {
                    var serverName = "gongdaobei:" + entry.getKey();
                    return this.server.constructServerInfo(serverName, socket, params.motd, false);
                });
                newServiceParams.put(entry.getKey(), params);
                (params.isRetired ? retiredServices : joiningServices).add(entry.getKey());
            }
            var oldServiceParams = this.serviceParams.getAndSet(Map.copyOf(newServiceParams));
            for (var oldAddrEntry : oldServiceParams.entrySet()) {
                var oldAddr = oldAddrEntry.getKey();
                if (oldAddrEntry.getValue().isRetired) {
                    retiredServices.remove(oldAddr);
                } else if (!joiningServices.remove(oldAddr) && !retiredServices.contains(oldAddr)) {
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
            GongdaobeiUtil.getHostAndPort(event.getTarget().getName(), "gongdaobei:", true).ifPresent(addr -> {
                var params = this.serviceParams.get().get(addr);
                if (params != null && !params.isRetired) {
                    var playerUniqueId = event.getPlayer().getUniqueId();
                    GongdaobeiUtil.setAffinityTarget(playerUniqueId, addr, this.conn, params.affinityMillis);
                }
            });
        }

        @EventHandler
        public void on(ServerConnectEvent event) {
            var player = event.getPlayer();
            if (player != null && event.getReason() == ServerConnectEvent.Reason.JOIN_PROXY) {
                var playerChoices = ServerEntry.from(player.getPendingConnection(), this.serviceParams.get());
                // if there is an affinity host which has space, send the player to that server
                var playerUniqueId = player.getUniqueId();
                var affinityHost = GongdaobeiUtil.getAffinityTarget(playerUniqueId, this.conn);
                var affinityParams = playerChoices.stream()
                        .filter(e -> affinityHost.filter(e.internalAddress()::equals).isPresent()).findFirst();
                if (affinityParams.isPresent()) {
                    var online = affinityParams.get().serviceParams().onlinePlayers;
                    var maximum = affinityParams.get().serviceParams().maximumPlayers;
                    if (online < maximum) {
                        this.logger.info("Affinity server found, send the player to the " +
                                "affinity server (" + affinityHost.get() + ", choices: " + playerChoices + ")");
                        event.setTarget(this.cachedServerInfoMap.get(affinityHost.get()));
                        return;
                    }
                }
                // weighted random choices
                var online = playerChoices.stream().mapToInt(e -> e.serviceParams().onlinePlayers).toArray();
                var maximum = playerChoices.stream().mapToInt(e -> e.serviceParams().maximumPlayers).toArray();
                var occupancyRateSummary = IntStream.range(0, online.length).mapToDouble(i ->
                        maximum[i] > 0 ? (online[i] + 1.0) / maximum[i] : 1.0).summaryStatistics();
                var highestOccupancyRate = occupancyRateSummary.getMin() > 1.0 ?
                        occupancyRateSummary.getMax() : Math.min(occupancyRateSummary.getMax(), 1.0);
                var weights = IntStream.range(0, maximum.length).mapToDouble(i ->
                        Math.max(0.0, maximum[i] * highestOccupancyRate - online[i])).toArray();
                var random = this.randomGenerator.nextDouble() * Arrays.stream(weights).map(w -> w * w).sum();
                var iterator = playerChoices.stream().iterator();
                for (var i = 0; i < online.length; ++i) {
                    var next = iterator.next();
                    random -= weights[i];
                    if (random < 0.0) {
                        this.logger.info("Load balancing performed, send the player to the target or " +
                                "fallback server (" + next.internalAddress() + ", choices: " + playerChoices + ")");
                        event.setTarget(this.cachedServerInfoMap.get(next.internalAddress()));
                        return;
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
            var limit = this.server.getConfig().getPlayerLimit();
            var playerChoices = ServerEntry.from(event.getConnection(), this.serviceParams.get());
            var online = playerChoices.stream().mapToInt(p -> p.serviceParams().onlinePlayers).sum();
            var maximum = playerChoices.stream().mapToInt(p -> p.serviceParams().maximumPlayers).sum();
            var pingForgeData = this.getDefaultPingForgeData();
            if (!playerChoices.isEmpty()) {
                var random = playerChoices.get(this.randomGenerator.nextInt(playerChoices.size())).serviceParams();
                if (random.pingForgeData instanceof JsonObject randomPingForgeData) {
                    randomPingForgeData.asMap().forEach(pingForgeData::add);
                }
                var motd = new TextComponent(TextComponent.fromLegacyText(random.motd));
                event.getResponse().setDescriptionComponent(motd);
            }
            if (SET_FORGE_DATA != null) {
                try {
                    SET_FORGE_DATA.invokeExact(event.getResponse(), pingForgeData);
                } catch (Throwable e) {
                    throw new RuntimeException(e);
                }
            }
            var maxWithLimit = limit > 0 ? Math.min(limit, maximum) : maximum;
            event.getResponse().setPlayers(new ServerPing.Players(maxWithLimit, online, new ServerPing.PlayerInfo[0]));
        }

        @Override
        public void close() {
            this.server.getPluginManager().unregisterListener(this);
            this.scheduledTask.cancel();
            this.redisClient.close();
        }

        private JsonObject getDefaultPingForgeData() {
            var pingForgeData = new JsonObject();
            // modern forge: { "fmlNetworkVersion": 3 }
            pingForgeData.add("fmlNetworkVersion", new JsonPrimitive(3));
            return pingForgeData;
        }
    }
}
