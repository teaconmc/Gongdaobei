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
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.player.PlayerChooseInitialServerEvent;
import com.velocitypowered.api.event.proxy.ProxyPingEvent;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.ServerInfo;
import com.velocitypowered.api.scheduler.ScheduledTask;
import io.lettuce.core.RedisClient;
import io.lettuce.core.codec.StringCodec;
import io.lettuce.core.masterreplica.MasterReplica;
import io.lettuce.core.masterreplica.StatefulRedisMasterReplicaConnection;
import io.lettuce.core.support.AsyncConnectionPoolSupport;
import io.lettuce.core.support.BoundedAsyncPool;
import io.lettuce.core.support.BoundedPoolConfig;
import io.prometheus.client.exporter.HTTPServer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.apache.commons.lang3.tuple.Pair;
import reactor.core.publisher.Mono;

import java.io.Closeable;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.teacon.gongdaobei.GongdaobeiVelocityPromMetrics.*;

public final class GongdaobeiVelocityNetworkEventHandler implements Runnable, Closeable {
    private final Logger logger;
    private final ProxyServer server;

    private final RedisClient redisClient;
    private final CompletionStage<BoundedAsyncPool<StatefulRedisMasterReplicaConnection<String, String>>> redisPool;

    private final ScheduledTask scheduledTask;
    private final Runnable unregisterCallback;
    private final AtomicInteger scheduleCounter = new AtomicInteger();

    private final Random randomGenerator;
    private final Runnable prometheusCloseCallback;
    private final Set<HostAndPort> externalAddressWhitelist;
    private final AtomicReference<GongdaobeiRegistry> registry;
    private final ConcurrentMap<HostAndPort, String> cachedServerNameMap = new ConcurrentHashMap<>();

    public GongdaobeiVelocityNetworkEventHandler(GongdaobeiVelocity plugin, GongdaobeiTomlConfig.Velocity config) {
        // velocity things
        this.logger = plugin.logger;
        this.server = plugin.server;

        // redis things
        this.redisClient = RedisClient.create();
        this.redisClient.setOptions(GongdaobeiUtil.getRedisClientOptions());
        this.redisPool = AsyncConnectionPoolSupport.createBoundedObjectPoolAsync(
                () -> MasterReplica.connectAsync(this.redisClient, StringCodec.UTF8,
                        config.discoveryRedisUri().getValue()), BoundedPoolConfig.create()).whenComplete((c, e) -> {
            var uri = GongdaobeiUtil.desensitizeRedisUri(config.discoveryRedisUri().getValue());
            if (c != null) {
                this.logger.info("Connected to the discovery redis server " +
                        "(" + uri + ", with " + c.getObjectCount() + " / " + c.getMaxTotal() + " pooled connections)");
            }
            if (e != null) {
                this.logger.log(Level.SEVERE, "Failed to connect to the discovery redis server (" +
                        uri + "), the server will run on offline mode and will not handle anything", e);
            }
        });

        // schedule things
        this.scheduledTask = this.server.getScheduler().buildTask(plugin, this)
                .delay(2500, TimeUnit.MILLISECONDS).repeat(2500, TimeUnit.MILLISECONDS).schedule();
        this.unregisterCallback = () -> this.server.getEventManager().unregisterListener(plugin, this);
        this.server.getEventManager().register(plugin, this);

        // registry things
        this.randomGenerator = new Random();
        this.externalAddressWhitelist = config.externalAddresses().stream()
                .map(GongdaobeiTomlConfig.AddressPattern::getValue).collect(Collectors.toSet());
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
        this.registry = new AtomicReference<>(new GongdaobeiRegistry.Builder(this::getOrCreateServerName).build());

        // command things
        this.server.getCommandManager().register(
                this.server.getCommandManager().metaBuilder("gongdaobei").aliases("go").build(),
                new GongdaobeiVelocityCommand(this.server, this.registry,
                        Mono.fromFuture(this.redisPool.toCompletableFuture(), true),
                        current -> this.server.getServer(this.cachedServerNameMap.get(current.internalAddress()))));

    }

    @Override
    public void run() {
        // update index
        var index = this.scheduleCounter.getAndIncrement();

        // update registry
        var conn = (StatefulRedisMasterReplicaConnection<String, String>) null;
        var registryBuilder = new GongdaobeiRegistry.Builder(this::getOrCreateServerName);
        try {
            // noinspection resource
            conn = this.redisPool.toCompletableFuture().join().acquire().join();
            GongdaobeiUtil.buildRegistry(registryBuilder, this.externalAddressWhitelist, conn.sync());
        } catch (CancellationException | CompletionException e) {
            this.logger.fine("The redis server is offline, no service data will be retrieved: " + e.getMessage());
        } finally {
            if (conn != null) {
                // noinspection resource
                this.redisPool.toCompletableFuture().join().release(conn).join();
            }
        }
        var registry = registryBuilder.build();
        var oldRegistry = this.registry.getAndSet(registry);

        // calculate fallback and targeted
        var fallback = Pair.of(
                registry.getFallbackInternalAddrOnline(true),
                registry.getFallbackInternalAddrOnline(false));
        var oldFallback = Pair.of(
                oldRegistry.getFallbackInternalAddrOnline(true),
                oldRegistry.getFallbackInternalAddrOnline(false));
        var targeted = Maps.toMap(
                registry.getTargetedExternalAddrOnline(), k -> Pair.of(
                        registry.getTargetedInternalAddrOnline(k, true),
                        registry.getTargetedInternalAddrOnline(k, false)));
        var oldTargeted = Maps.toMap(
                oldRegistry.getTargetedExternalAddrOnline(), k -> Pair.of(
                        oldRegistry.getTargetedInternalAddrOnline(k, true),
                        oldRegistry.getTargetedInternalAddrOnline(k, false)));

        // calculate changed online services
        var onlineServices = registry.getInternalAddrOnline();
        var joiningServices = Sets.difference(onlineServices, oldRegistry.getInternalAddrOnline());
        var offlineServices = Sets.difference(oldRegistry.getInternalAddrOnline(), onlineServices);
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
        onlinePlayers.set(onlineSum);
        maximumPlayers.set(maximumSum);
        serviceInstances.set(onlineServices.size());

        // push prom metrics of all the servers
        for (var internalAddr : onlineServices) {
            // noinspection DuplicatedCode
            var params = registry.getParams(internalAddr);
            var serverName = this.cachedServerNameMap.get(internalAddr);
            servicePerTick.labels(serverName).set(params.tickMillis / 1000.0);
        }
        // noinspection DuplicatedCode
        for (var internalAddr : offlineServices) {
            var serverName = this.cachedServerNameMap.get(internalAddr);
            servicePerTick.remove(serverName);
        }

        // push prom metrics of fallback servers
        var fallbackOnline = fallback.getRight().stream().mapToInt(k -> registry.getParams(k).onlinePlayers).sum();
        var fallbackMaximum = fallback.getRight().stream().mapToInt(k -> registry.getParams(k).maximumPlayers).sum();
        fallbackOnlinePlayers.set(fallbackOnline);
        fallbackMaximumPlayers.set(fallbackMaximum);
        fallbackServiceInstances.set(fallback.getRight().size());
        latestFallbackServiceInstances.set(fallback.getLeft().size());
        for (var internalAddr : fallback.getRight()) {
            // noinspection DuplicatedCode
            var params = registry.getParams(internalAddr);
            var serverName = this.cachedServerNameMap.get(internalAddr);
            fallbackServicePerTick.labels(serverName).set(params.tickMillis / 1000.0);
        }
        var offlineFallbacks = Sets.difference(oldFallback.getRight(), fallback.getRight());
        // noinspection DuplicatedCode
        for (var internalAddr : offlineFallbacks) {
            var serverName = this.cachedServerNameMap.get(internalAddr);
            fallbackServicePerTick.remove(serverName);
        }

        // push prom metrics of targeted servers
        for (var entry : targeted.entrySet()) {
            var current = entry.getValue();
            var externalAddr = entry.getKey();
            var targetedOnline = current.getRight().stream().mapToInt(k -> registry.getParams(k).onlinePlayers).sum();
            var targetedMaximum = current.getRight().stream().mapToInt(k -> registry.getParams(k).maximumPlayers).sum();
            targetedOnlinePlayers.labels(externalAddr.toString()).set(targetedOnline);
            targetedMaximumPlayers.labels(externalAddr.toString()).set(targetedMaximum);
            targetedServiceInstances.labels(externalAddr.toString()).set(current.getRight().size());
            latestTargetedServiceInstances.labels(externalAddr.toString()).set(current.getLeft().size());
            for (var internalAddr : current.getRight()) {
                var params = registry.getParams(internalAddr);
                var serverName = this.cachedServerNameMap.get(internalAddr);
                targetedServicePerTick.labels(externalAddr.toString(), serverName).set(params.tickMillis / 1000.0);
            }
            var prev = oldTargeted.get(externalAddr);
            var offline = prev != null ? Sets.difference(prev.getRight(), current.getRight()) : Set.<HostAndPort>of();
            // noinspection DuplicatedCode
            for (var internalAddr : offline) {
                var serverName = this.cachedServerNameMap.get(internalAddr);
                targetedServicePerTick.remove(externalAddr.toString(), serverName);
            }
        }
        for (var externalAddr : Sets.difference(oldTargeted.keySet(), targeted.keySet())) {
            targetedOnlinePlayers.remove(externalAddr.toString());
            targetedMaximumPlayers.remove(externalAddr.toString());
            targetedServiceInstances.remove(externalAddr.toString());
            latestTargetedServiceInstances.remove(externalAddr.toString());
            var prev = oldTargeted.get(externalAddr);
            var offline = prev != null ? prev.getRight() : Set.<HostAndPort>of();
            // noinspection DuplicatedCode
            for (var addr : offline) {
                var serverName = this.cachedServerNameMap.get(addr);
                targetedServicePerTick.remove(externalAddr.toString(), serverName);
            }
        }
    }

    @Subscribe
    public void on(DisconnectEvent event) {
        var conn = (StatefulRedisMasterReplicaConnection<String, String>) null;
        try {
            // noinspection resource
            conn = this.redisPool.toCompletableFuture().join().acquire().join();
            GongdaobeiUtil.refreshAffinity(event.getPlayer().getUniqueId(), this::getConfirmationAffinity, conn.sync());
        } catch (CancellationException | CompletionException e) {
            this.logger.fine("The redis server is offline, no service data will be retrieved: " + e.getMessage());
        } finally {
            if (conn != null) {
                // noinspection resource
                this.redisPool.toCompletableFuture().join().release(conn).join();
            }
        }
    }

    @Subscribe
    public void on(PlayerChooseInitialServerEvent event) {
        var player = event.getPlayer();
        var playerExternal = player.getVirtualHost().orElse(null);
        var playerChoices = new LinkedHashMap<GongdaobeiConfirmation, GongdaobeiServiceParams>();
        GongdaobeiConfirmation.collect(playerExternal, this.registry.get(), playerChoices::put);
        // if there is an affinity host which has space, send the player to that server
        var playerName = player.getUsername();
        var playerUniqueId = player.getUniqueId();
        var conn = (StatefulRedisMasterReplicaConnection<String, String>) null;
        try {
            // noinspection resource
            conn = this.redisPool.toCompletableFuture().join().acquire().join();
            var affinity = GongdaobeiUtil.fetchAffinity(playerUniqueId, conn.sync());
            var affinityParams = affinity.map(playerChoices::get);
            if (affinityParams.isPresent()) {
                var online = affinityParams.get().onlinePlayers;
                var maximum = affinityParams.get().maximumPlayers;
                if (online < maximum) {
                    this.logger.info("Affinity server found, send " + playerName + " (" + playerUniqueId
                            + ") to the affinity server (" + affinity.get() + ", choices: " + playerChoices + ")");
                    GongdaobeiUtil.confirmPlayer(playerUniqueId, affinity.get(), conn.sync());
                    var serverName = this.cachedServerNameMap.get(affinity.get().internalAddress());
                    event.setInitialServer(this.server.getServer(serverName).orElse(null));
                    totalLoginsWithAffinity.inc();
                    totalLogins.inc();
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
                    GongdaobeiUtil.confirmPlayer(playerUniqueId, balanced, conn.sync());
                    var serverName = this.cachedServerNameMap.get(balanced.internalAddress());
                    event.setInitialServer(this.server.getServer(serverName).orElse(null));
                    totalLogins.inc();
                    return;
                }
            }
        } catch (CancellationException | CompletionException e) {
            this.logger.fine("The redis server is offline, no service data will be retrieved: " + e.getMessage());
            player.disconnect(Component.translatable("velocity.error.no-available-servers"));
            return;
        } finally {
            if (conn != null) {
                // noinspection resource
                this.redisPool.toCompletableFuture().join().release(conn).join();
            }
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
        var playerChoices = new LinkedHashMap<GongdaobeiConfirmation, GongdaobeiServiceParams>();
        GongdaobeiConfirmation.collect(playerExternal, this.registry.get(), playerChoices::put);
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
        totalPings.inc();
    }

    @Override
    public void close() {
        this.prometheusCloseCallback.run();
        this.unregisterCallback.run();
        this.scheduledTask.cancel();
        this.redisPool.thenCompose(BoundedAsyncPool::closeAsync).toCompletableFuture().join();
        this.redisClient.shutdownAsync().join();
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
        var registry = this.registry.get();
        var internalAddr = confirmation.internalAddress();
        var internalAddrOnline = registry.getInternalAddrOnline();
        return internalAddrOnline.contains(internalAddr) ? registry.getParams(internalAddr).affinityMillis : 0;
    }
}
