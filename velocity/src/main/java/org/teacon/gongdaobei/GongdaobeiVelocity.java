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

import com.google.common.collect.Iterators;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.google.common.net.HostAndPort;
import com.google.common.util.concurrent.Runnables;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.google.inject.Inject;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.player.PlayerChooseInitialServerEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyPingEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.ServerInfo;
import com.velocitypowered.api.scheduler.ScheduledTask;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.codec.StringCodec;
import io.lettuce.core.masterreplica.MasterReplica;
import io.prometheus.client.Counter;
import io.prometheus.client.Gauge;
import io.prometheus.client.exporter.HTTPServer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.apache.commons.lang3.tuple.Pair;

import java.io.Closeable;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

@Plugin(id = "gongdaobei",
        name = GongdaobeiVelocityParameters.NAME,
        version = GongdaobeiVelocityParameters.VERSION,
        authors = GongdaobeiVelocityParameters.AUTHORS,
        description = GongdaobeiVelocityParameters.DESCRIPTION)
public final class GongdaobeiVelocity {
    private GongdaobeiTomlConfig.Velocity config;
    private Handler handler;

    private final ProxyServer server;
    private final Logger logger;
    private final Path dataDir;

    @Inject
    public GongdaobeiVelocity(ProxyServer server, Logger logger, @DataDirectory Path dataDir) {
        this.server = server;
        this.logger = logger;
        this.dataDir = dataDir;
    }

    @Subscribe
    public void onEnable(ProxyInitializeEvent event) {
        try {
            var dataDir = Files.exists(this.dataDir) ? this.dataDir : Files.createDirectories(this.dataDir);
            this.logger.info("Loading from the configuration file ...");
            var file = dataDir.resolve("gongdaobei.toml");
            this.config = GongdaobeiTomlConfig.Velocity.load(file).save(file);
            var redisUri = this.config.discoveryRedisUri();
            var desensitizeRedisUri = GongdaobeiUtil.desensitizeRedisUri(redisUri.getValue());
            this.logger.info("- Discovery Redis URI: " + desensitizeRedisUri + " (resolved from " + redisUri + ")");
            this.handler = new Handler(this, this.config);
        } catch (IOException e) {
            throw new IllegalArgumentException(e);
        }
    }

    @Subscribe
    public void onDisable(ProxyShutdownEvent event) {
        try {
            var dataDir = Files.exists(this.dataDir) ? this.dataDir : Files.createDirectories(this.dataDir);
            this.logger.info("Saving to the configuration file ...");
            var file = dataDir.resolve("gongdaobei.toml");
            this.config.save(file);
            this.handler.close();
        } catch (IOException e) {
            throw new IllegalArgumentException(e);
        }
    }

    public static final class PromMetrics {
        private static final Counter totalPings;
        private static final Counter totalLogins;
        private static final Counter totalLoginsWithAffinity;
        private static final Gauge onlinePlayers;
        private static final Gauge fallbackOnlinePlayers;
        private static final Gauge targetedOnlinePlayers;
        private static final Gauge maximumPlayers;
        private static final Gauge fallbackMaximumPlayers;
        private static final Gauge targetedMaximumPlayers;
        private static final Gauge serviceInstances;
        private static final Gauge fallbackServiceInstances;
        private static final Gauge targetedServiceInstances;
        private static final Gauge latestFallbackServiceInstances;
        private static final Gauge latestTargetedServiceInstances;
        private static final Gauge servicePerTick;
        private static final Gauge fallbackServicePerTick;
        private static final Gauge targetedServicePerTick;

        static {
            totalPings = Counter.build(
                    "gongdaobei_pings_total",
                    "Total ping requests by clients").register();
            totalLogins = Counter.build(
                    "gongdaobei_logins_total",
                    "Total login requests by clients").register();
            totalLoginsWithAffinity = Counter.build(
                    "gongdaobei_logins_with_affinity_total",
                    "Total login requests by clients with affinity").register();
            onlinePlayers = Gauge.build(
                    "gongdaobei_online_players",
                    "Online players of all the servers").register();
            fallbackOnlinePlayers = Gauge.build(
                    "gongdaobei_fallback_online_players",
                    "Online players of fallback servers").register();
            targetedOnlinePlayers = Gauge.build(
                    "gongdaobei_targeted_online_players",
                    "Online players of servers with the same external address").labelNames("address").register();
            maximumPlayers = Gauge.build(
                    "gongdaobei_maximum_players",
                    "Maximum players of all the servers").register();
            fallbackMaximumPlayers = Gauge.build(
                    "gongdaobei_fallback_maximum_players",
                    "Maximum players of servers marked as fallback servers").register();
            targetedMaximumPlayers = Gauge.build(
                    "gongdaobei_targeted_maximum_players",
                    "Maximum players of servers with the same external address").labelNames("address").register();
            serviceInstances = Gauge.build(
                    "gongdaobei_service_instances",
                    "The instance count of servers").register();
            fallbackServiceInstances = Gauge.build(
                    "gongdaobei_fallback_service_instances",
                    "The instance count of fallback servers").register();
            targetedServiceInstances = Gauge.build(
                    "gongdaobei_targeted_service_instances",
                    "The instance count of servers with the same external address").labelNames("address").register();
            latestFallbackServiceInstances = Gauge.build(
                    "gongdaobei_latest_fallback_service_instances",
                    "The instance count of fallback servers whose version is latest").register();
            latestTargetedServiceInstances = Gauge.build(
                    "gongdaobei_latest_targeted_service_instances",
                    "The instance count of servers with the same external address whose version is latest").labelNames("address").register();
            servicePerTick = Gauge.build(
                    "gongdaobei_service_tick_duration_seconds",
                    "The time spent per tick in seconds").labelNames("name").register();
            fallbackServicePerTick = Gauge.build(
                    "gongdaobei_fallback_service_tick_duration_seconds",
                    "The time spent per tick in seconds of fallback servers").labelNames("name").register();
            targetedServicePerTick = Gauge.build(
                    "gongdaobei_targeted_service_tick_duration_seconds",
                    "The time spent per tick in seconds of servers grouped by external addresses").labelNames("address", "name").register();
        }
    }

    public static final class Handler implements Runnable, Closeable {
        private final Logger logger;
        private final ProxyServer server;
        private final RedisClient redisClient;
        private final ScheduledTask scheduledTask;
        private final Runnable unregisterCallback;
        private final Runnable prometheusCloseCallback;
        private final Set<HostAndPort> externalAddressWhitelist;
        private final AtomicReference<GongdaobeiRegistry> currentRegistry;
        private final Random randomGenerator = new Random();
        private final AtomicInteger scheduleCounter = new AtomicInteger();
        private final CompletableFuture<? extends StatefulRedisConnection<String, String>> conn;
        private final ConcurrentMap<HostAndPort, String> cachedServerNameMap = new ConcurrentHashMap<>();

        public Handler(GongdaobeiVelocity plugin, GongdaobeiTomlConfig.Velocity config) {
            this.logger = plugin.logger;
            this.server = plugin.server;
            this.redisClient = RedisClient.create();
            this.redisClient.setOptions(GongdaobeiUtil.getRedisClientOptions());
            this.externalAddressWhitelist = config.externalAddresses().stream()
                    .map(GongdaobeiTomlConfig.AddressPattern::getValue).collect(Collectors.toSet());
            this.conn = MasterReplica.connectAsync(
                    this.redisClient, StringCodec.UTF8, config.discoveryRedisUri().getValue()).whenComplete((c, e) -> {
                var uri = GongdaobeiUtil.desensitizeRedisUri(config.discoveryRedisUri().getValue());
                if (c != null) {
                    this.logger.info("Connected to the discovery redis server (" + uri + ")");
                }
                if (e != null) {
                    this.logger.log(Level.SEVERE, "Failed to connect to the discovery redis server (" +
                            uri + "), the server will run on offline mode and will not handle anything", e);
                }
            });
            this.scheduledTask = this.server.getScheduler().buildTask(plugin, this)
                    .delay(2500, TimeUnit.MILLISECONDS).repeat(2500, TimeUnit.MILLISECONDS).schedule();
            this.unregisterCallback = () -> this.server.getEventManager().unregisterListener(plugin, this);
            this.server.getEventManager().register(plugin, this);
            // noinspection UnstableApiUsage
            var prometheusCloseCallback = Runnables.doNothing();
            var httpServerPort = config.prometheusServerPort();
            if (httpServerPort > 0) {
                try {
                    // noinspection resource
                    var httpServer = new HTTPServer.Builder().withPort(httpServerPort).build();
                    this.logger.info("Launched the prometheus server at port " + httpServerPort);
                    prometheusCloseCallback = httpServer::close;
                } catch (IOException e) {
                    this.logger.log(Level.SEVERE, "Failed to launch the prometheus server at port " + httpServerPort, e);
                }
            }
            this.prometheusCloseCallback = prometheusCloseCallback;
            this.currentRegistry = new AtomicReference<>(new GongdaobeiRegistry.Builder(this::getOrCreateServerName).build());
        }

        @Override
        public void run() {
            // update index
            var index = this.scheduleCounter.getAndIncrement();
            // update registry
            var registryBuilder = new GongdaobeiRegistry.Builder(this::getOrCreateServerName);
            try {
                GongdaobeiUtil.buildRegistry(registryBuilder, this.externalAddressWhitelist, this.conn.get().sync());
            } catch (ExecutionException | InterruptedException e) {
                this.logger.fine("The redis server is offline, no service data will be retrieved: " + e.getMessage());
            }
            var registry = registryBuilder.build();
            var prevRegistry = this.currentRegistry.getAndSet(registry);
            // calculate fallback and targeted
            var currentFallback = Pair.of(
                    registry.getFallbackInternalAddrOnline(true),
                    registry.getFallbackInternalAddrOnline(false));
            var previousFallback = Pair.of(
                    prevRegistry.getFallbackInternalAddrOnline(true),
                    prevRegistry.getFallbackInternalAddrOnline(false));
            var currentTargeted = Maps.toMap(
                    registry.getTargetedExternalAddrOnline(), k -> Pair.of(
                            registry.getTargetedInternalAddrOnline(k, true),
                            registry.getTargetedInternalAddrOnline(k, false)));
            var previousTargeted = Maps.toMap(
                    prevRegistry.getTargetedExternalAddrOnline(), k -> Pair.of(
                            prevRegistry.getTargetedInternalAddrOnline(k, true),
                            prevRegistry.getTargetedInternalAddrOnline(k, false)));
            // calculate changed online services
            var onlineServices = registry.getInternalAddrOnline();
            var joiningServices = Sets.difference(onlineServices, prevRegistry.getInternalAddrOnline());
            var offlineServices = Sets.difference(prevRegistry.getInternalAddrOnline(), onlineServices);
            var retiredServices = Sets.intersection(offlineServices, registry.getInternalAddrRetired());
            var missingServices = Sets.difference(offlineServices, registry.getInternalAddrRetired());
            // add logs for changes
            if (!missingServices.isEmpty()) {
                this.logger.warning("Registered service status changed at update " + index + " (retired: " +
                        retiredServices + ", joining: " + joiningServices + ", missing: " + missingServices + ")");
            } else if (joiningServices.size() + retiredServices.size() > 0) {
                this.logger.info("Registered service status changed at update " + index + " (retired: " +
                        retiredServices + ", joining: " + joiningServices + ", missing: " + missingServices + ")");
            }
            // push prom metrics of total
            var onlineSum = onlineServices.stream().mapToInt(k -> registry.getParams(k).onlinePlayers).sum();
            var maximumSum = onlineServices.stream().mapToInt(k -> registry.getParams(k).maximumPlayers).sum();
            PromMetrics.onlinePlayers.set(onlineSum);
            PromMetrics.maximumPlayers.set(maximumSum);
            PromMetrics.serviceInstances.set(onlineServices.size());
            // push prom metrics of all the servers
            for (var internalAddr : onlineServices) {
                var params = registry.getParams(internalAddr);
                var serverName = this.cachedServerNameMap.get(internalAddr);
                PromMetrics.servicePerTick.labels(serverName).set(params.tickMillis / 1000.0);
            }
            for (var internalAddr : offlineServices) {
                var serverName = this.cachedServerNameMap.get(internalAddr);
                PromMetrics.servicePerTick.remove(serverName);
            }
            // push prom metrics of fallback servers
            var fallbackOnlineSum = currentFallback.getRight().stream().mapToInt(k -> registry.getParams(k).onlinePlayers).sum();
            var fallbackMaximumSum = currentFallback.getRight().stream().mapToInt(k -> registry.getParams(k).maximumPlayers).sum();
            PromMetrics.fallbackOnlinePlayers.set(fallbackOnlineSum);
            PromMetrics.fallbackMaximumPlayers.set(fallbackMaximumSum);
            PromMetrics.fallbackServiceInstances.set(currentFallback.getRight().size());
            PromMetrics.latestFallbackServiceInstances.set(currentFallback.getLeft().size());
            for (var internalAddr : currentFallback.getRight()) {
                var params = registry.getParams(internalAddr);
                var serverName = this.cachedServerNameMap.get(internalAddr);
                PromMetrics.fallbackServicePerTick.labels(serverName).set(params.tickMillis / 1000.0);
            }
            var offlineFallbacks = Sets.difference(previousFallback.getRight(), currentFallback.getRight());
            for (var internalAddr : offlineFallbacks) {
                var serverName = this.cachedServerNameMap.get(internalAddr);
                PromMetrics.fallbackServicePerTick.remove(serverName);
            }
            // push prom metrics of targeted servers
            for (var entry : currentTargeted.entrySet()) {
                var current = entry.getValue();
                var externalAddr = entry.getKey();
                var targetedOnlineSum = current.getRight().stream().mapToInt(k -> registry.getParams(k).onlinePlayers).sum();
                var targetedMaximumSum = current.getRight().stream().mapToInt(k -> registry.getParams(k).maximumPlayers).sum();
                PromMetrics.targetedOnlinePlayers.labels(externalAddr.toString()).set(targetedOnlineSum);
                PromMetrics.targetedMaximumPlayers.labels(externalAddr.toString()).set(targetedMaximumSum);
                PromMetrics.targetedServiceInstances.labels(externalAddr.toString()).set(current.getRight().size());
                PromMetrics.latestTargetedServiceInstances.labels(externalAddr.toString()).set(current.getLeft().size());
                for (var internalAddr : current.getRight()) {
                    var params = registry.getParams(internalAddr);
                    var serverName = this.cachedServerNameMap.get(internalAddr);
                    PromMetrics.targetedServicePerTick.labels(externalAddr.toString(), serverName).set(params.tickMillis / 1000.0);
                }
                var prev = previousTargeted.get(externalAddr);
                var offline = prev != null ? Sets.difference(prev.getRight(), current.getRight()) : Set.<HostAndPort>of();
                for (var internalAddr : offline) {
                    var serverName = this.cachedServerNameMap.get(internalAddr);
                    PromMetrics.targetedServicePerTick.remove(externalAddr.toString(), serverName);
                }
            }
            for (var externalAddr : Sets.difference(previousTargeted.keySet(), currentTargeted.keySet())) {
                PromMetrics.targetedOnlinePlayers.remove(externalAddr.toString());
                PromMetrics.targetedMaximumPlayers.remove(externalAddr.toString());
                PromMetrics.targetedServiceInstances.remove(externalAddr.toString());
                PromMetrics.latestTargetedServiceInstances.remove(externalAddr.toString());
                var prev = previousTargeted.get(externalAddr);
                var offline = prev != null ? prev.getRight() : Set.<HostAndPort>of();
                for (var addr : offline) {
                    var serverName = this.cachedServerNameMap.get(addr);
                    PromMetrics.targetedServicePerTick.remove(externalAddr.toString(), serverName);
                }
            }
        }

        @Subscribe
        public void on(DisconnectEvent event) {
            try {
                var cmd = this.conn.get().sync();
                GongdaobeiUtil.refreshAffinity(event.getPlayer().getUniqueId(), this::getConfirmationAffinity, cmd);
            } catch (ExecutionException | InterruptedException e) {
                this.logger.fine("The redis server is offline, no service data will be retrieved: " + e.getMessage());
            }
        }

        @Subscribe
        public void on(PlayerChooseInitialServerEvent event) {
            var player = event.getPlayer();
            var playerExternal = player.getVirtualHost().orElse(null);
            var playerChoices = GongdaobeiConfirmation.filter(playerExternal, this.currentRegistry.get());
            // if there is an affinity host which has space, send the player to that server
            var playerName = player.getUsername();
            var playerUniqueId = player.getUniqueId();
            try {
                var cmd = this.conn.get().sync();
                var affinity = GongdaobeiUtil.fetchAffinity(playerUniqueId, cmd);
                var affinityParams = affinity.map(playerChoices::get);
                if (affinityParams.isPresent()) {
                    var online = affinityParams.get().onlinePlayers;
                    var maximum = affinityParams.get().maximumPlayers;
                    if (online < maximum) {
                        this.logger.info("Affinity server found, send " + playerName + " (" + playerUniqueId
                                + ") to the affinity server (" + affinity.get() + ", choices: " + playerChoices + ")");
                        GongdaobeiUtil.confirmPlayer(playerUniqueId, affinity.get(), cmd);
                        var serverName = this.cachedServerNameMap.get(affinity.get().internalAddress());
                        event.setInitialServer(this.server.getServer(serverName).orElse(null));
                        PromMetrics.totalLoginsWithAffinity.inc();
                        PromMetrics.totalLogins.inc();
                        return;
                    }
                }
                // weighted random choices
                var online = playerChoices.values().stream().mapToInt(e -> e.onlinePlayers).toArray();
                var maximum = playerChoices.values().stream().mapToInt(e -> e.maximumPlayers).toArray();
                var occupancyRateSummary = IntStream.range(0, online.length).mapToDouble(i ->
                        maximum[i] > 0 ? (online[i] + 1.0) / maximum[i] : 1.0).summaryStatistics();
                var highestOccupancyRate = occupancyRateSummary.getMin() > 1.0 ?
                        occupancyRateSummary.getMax() : Math.min(occupancyRateSummary.getMax(), 1.0);
                var weights = IntStream.range(0, maximum.length).mapToDouble(i ->
                        Math.max(0.0, maximum[i] * highestOccupancyRate - online[i])).toArray();
                var random = this.randomGenerator.nextDouble() * Arrays.stream(weights).map(w -> w * w).sum();
                var iterator = playerChoices.entrySet().iterator();
                for (var i = 0; i < online.length; ++i) {
                    var next = iterator.next();
                    random -= weights[i] * weights[i];
                    if (random < 0.0) {
                        var balanced = next.getKey();
                        this.logger.info("Load balancing performed, send " + playerName + " (" + playerUniqueId +
                                ") to the chosen balanced server (" + balanced + ", choices: " + playerChoices + ")");
                        GongdaobeiUtil.confirmPlayer(playerUniqueId, balanced, cmd);
                        var serverName = this.cachedServerNameMap.get(balanced.internalAddress());
                        event.setInitialServer(this.server.getServer(serverName).orElse(null));
                        PromMetrics.totalLogins.inc();
                        return;
                    }
                }
            } catch (ExecutionException | InterruptedException e) {
                this.logger.fine("The redis server is offline, no service data will be retrieved: " + e.getMessage());
                player.disconnect(Component.translatable("velocity.error.no-available-servers"));
                return;
            }
            // if there is no choice (such that all the choices are full), disconnect
            player.disconnect(Component.translatable("velocity.error.no-available-servers"));
            this.logger.warning("No choice found, throw " + playerName + " (" + playerUniqueId + ") outside");
            event.setInitialServer(null);
        }

        @Subscribe
        public void on(ProxyPingEvent event) {
            var builder = event.getPing().asBuilder();
            var limit = this.server.getConfiguration().getShowMaxPlayers();
            var playerExternal = event.getConnection().getVirtualHost().orElse(null);
            var playerChoices = GongdaobeiConfirmation.filter(playerExternal, this.currentRegistry.get());
            var online = playerChoices.values().stream().mapToInt(p -> p.onlinePlayers).sum();
            var maximum = playerChoices.values().stream().mapToInt(p -> p.maximumPlayers).sum();
            var pingForgeData = this.getDefaultPingForgeData();
            if (!playerChoices.isEmpty()) {
                var randomIndex = this.randomGenerator.nextInt(playerChoices.size());
                var random = Iterators.get(playerChoices.values().stream().iterator(), randomIndex);
                if (random.pingForgeData instanceof JsonObject randomPingForgeData) {
                    randomPingForgeData.asMap().forEach(pingForgeData::add);
                }
                var motd = LegacyComponentSerializer.legacyAmpersand().deserialize(random.motd);
                builder.description(motd);
            }
            var maxWithLimit = limit > 0 ? Math.min(limit, maximum) : maximum;
            builder.maximumPlayers(maxWithLimit).onlinePlayers(online).clearSamplePlayers();
            event.setPing(builder.build());
            PromMetrics.totalPings.inc();
        }

        @Override
        public void close() {
            this.prometheusCloseCallback.run();
            this.unregisterCallback.run();
            this.scheduledTask.cancel();
            this.redisClient.close();
        }

        private JsonObject getDefaultPingForgeData() {
            var pingForgeData = new JsonObject();
            // modern forge: { "fmlNetworkVersion": 3 }
            pingForgeData.add("fmlNetworkVersion", new JsonPrimitive(3));
            return pingForgeData;
        }

        private String getOrCreateServerName(HostAndPort internalAddr, GongdaobeiServiceParams params) {
            return this.cachedServerNameMap.compute(internalAddr, (k, v) -> {
                var newName = "gongdaobei:" + params.hostname + ":" + k;
                if (v != null && !v.equals(newName)) {
                    this.logger.info("Unregistered velocity server info object: " + v);
                    this.server.getServer(v).ifPresent(s -> this.server.unregisterServer(s.getServerInfo()));
                }
                if (v == null || !v.equals(newName)) {
                    var socket = InetSocketAddress.createUnresolved(k.getHost(), k.getPort());
                    var serverInfo = new ServerInfo(newName, socket);
                    this.logger.info("Registered velocity server info object: " + serverInfo.getName());
                    this.server.registerServer(serverInfo);
                    v = newName;
                }
                return v;
            });
        }

        private long getConfirmationAffinity(GongdaobeiConfirmation confirmation) {
            var registry = this.currentRegistry.get();
            var internalAddr = confirmation.internalAddress();
            var internalAddrOnline = registry.getInternalAddrOnline();
            return internalAddrOnline.contains(internalAddr) ? registry.getParams(internalAddr).affinityMillis : 0;
        }
    }
}
