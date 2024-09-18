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
import com.google.gson.JsonNull;
import com.mojang.authlib.GameProfile;
import com.mojang.logging.LogUtils;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.codec.StringCodec;
import io.lettuce.core.masterreplica.MasterReplica;
import net.minecraft.FieldsAreNonnullByDefault;
import net.minecraft.MethodsReturnNonnullByDefault;
import net.minecraft.Util;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.server.dedicated.DedicatedPlayerList;
import net.minecraft.server.dedicated.DedicatedServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerAboutToStartEvent;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;

import javax.annotation.Nullable;
import javax.annotation.ParametersAreNonnullByDefault;
import java.io.Closeable;
import java.io.IOException;
import java.net.InetAddress;
import java.net.SocketAddress;
import java.net.UnknownHostException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

import static net.minecraft.world.level.storage.LevelResource.PLAYER_ADVANCEMENTS_DIR;
import static net.minecraft.world.level.storage.LevelResource.PLAYER_STATS_DIR;

@Mod(GongdaobeiNeoForge.ID)
public final class GongdaobeiNeoForge {
    public static final String ID = "gongdaobei";
    private static final Logger LOGGER = LogUtils.getLogger();

    private GongdaobeiTomlConfig.Service config;
    private LockGuardedPlayerList lockGuardedPlayerList;

    public GongdaobeiNeoForge() {
        NeoForge.EVENT_BUS.addListener(this::onAboutToStart);
        NeoForge.EVENT_BUS.addListener(this::onStarting);
        NeoForge.EVENT_BUS.addListener(this::onStopping);
    }

    private void onAboutToStart(ServerAboutToStartEvent event) {
        if (event.getServer() instanceof DedicatedServer server) {
            LOGGER.info("Loading from the configuration file ...");
            var confFile = FMLPaths.CONFIGDIR.get().resolve(Path.of("gongdaobei.toml"));
            this.config = GongdaobeiTomlConfig.Service.load(confFile).save(confFile);
            this.lockGuardedPlayerList = new LockGuardedPlayerList(server, this.config);
            if (this.config.syncPlayersFromRedis()) {
                server.setPlayerList(this.lockGuardedPlayerList);
            } else {
                server.addTickable(() -> this.lockGuardedPlayerList.tickSubmitService(server.getTickCount()));
            }
        }
    }

    private void onStarting(ServerStartingEvent event) {
        if (event.getServer() instanceof DedicatedServer) {
            LOGGER.info("- Discovery Redis URI: {} (resolved from {})",
                    GongdaobeiUtil.desensitizeRedisUri(this.config.discoveryRedisUri().getValue()), this.config.discoveryRedisUri().toString());
            LOGGER.info("- Internal Address: {} (resolved from {})",
                    this.config.internalAddress().getValue(), this.config.internalAddress());
            LOGGER.info("- External Addresses: {} (resolved from {})",
                    this.config.externalAddresses().stream().map(GongdaobeiTomlConfig.AddressPattern::getValue).toList(),
                    this.config.externalAddresses().stream().map(GongdaobeiTomlConfig.AddressPattern::toString).toList());
            LOGGER.info("- Is Fallback Server: {}",
                    this.config.isFallbackServer() ? "TRUE" : "FALSE");
            LOGGER.info("- Sync Players: {}",
                    this.config.syncPlayersFromRedis() ? "TRUE" : "FALSE");
            LOGGER.info("- Version: {} (resolved from {})",
                    StringUtils.defaultIfEmpty(this.config.version().resolve().toString(), "undefined"), this.config.version());
            LOGGER.info("- Affinity Millis: {}",
                    this.config.affinityMillis());
        }
    }

    private void onStopping(ServerStoppingEvent event) {
        if (event.getServer() instanceof DedicatedServer) {
            LOGGER.info("Saving to the configuration file ...");
            var confFile = FMLPaths.CONFIGDIR.get().resolve(Path.of("gongdaobei.toml"));
            this.config.save(confFile);
            this.lockGuardedPlayerList.close();
        }
    }

    @FieldsAreNonnullByDefault
    @MethodsReturnNonnullByDefault
    @ParametersAreNonnullByDefault
    private static class LockGuardedPlayerList extends DedicatedPlayerList implements Closeable {
        private final String hostname;
        private final RedisClient redisClient;
        private final GongdaobeiTomlConfig.Service config;
        private final CompletableFuture<? extends StatefulRedisConnection<String, String>> conn;

        private Map.Entry<HostAndPort, GongdaobeiServiceParams> serviceParamsEntry;

        public LockGuardedPlayerList(DedicatedServer server, GongdaobeiTomlConfig.Service config) {
            super(server, server.registries(), server.playerDataStorage);
            try {
                this.hostname = InetAddress.getLocalHost().getHostName();
                Preconditions.checkArgument(this.hostname.matches("[A-Za-z0-9-.]+"));
            } catch (UnknownHostException e) {
                throw new IllegalArgumentException(e);
            }
            this.redisClient = Util.make(
                    RedisClient.create(), c -> c.setOptions(GongdaobeiUtil.getRedisClientOptions()));
            this.conn = MasterReplica.connectAsync(
                    this.redisClient, StringCodec.UTF8, config.discoveryRedisUri().getValue()).whenComplete((c, e) -> {
                var uri = GongdaobeiUtil.desensitizeRedisUri(config.discoveryRedisUri().getValue());
                if (c != null) {
                    LOGGER.info("Connected to the discovery redis server ({})", uri);
                }
                if (e != null) {
                    LOGGER.error("Failed to connect to the discovery redis server ({}), " +
                            "the server will run on offline mode and will not handle anything", uri, e);
                }
            });
            this.config = config;
            this.serviceParamsEntry = this.refreshParamsEntry(server.getTickCount(), false);
        }

        private double twentyTicksAvgMillis(long[] tickTimeNanos, int tickCount) {
            var twentyTicksSumNanos = 0L;
            var tickTimeCount = tickTimeNanos.length;
            for (var i = tickCount - 20; i < tickCount; ++i) {
                twentyTicksSumNanos += tickTimeNanos[Math.floorMod(i, tickTimeCount)];
            }
            return twentyTicksSumNanos * (0.000001 / 20);
        }

        private Map.Entry<HostAndPort, GongdaobeiServiceParams> refreshParamsEntry(int tickCount, boolean retired) {
            var key = this.config.internalAddress().getValue().withDefaultPort(this.getServer().getPort());
            return Map.entry(key, new GongdaobeiServiceParams(
                    this.hostname, this.config, retired, this.getServer().getMotd(),
                    this.twentyTicksAvgMillis(this.getServer().getTickTimesNanos(), tickCount),
                    this.getServer().getPlayerCount(), this.getServer().getMaxPlayers(), JsonNull.INSTANCE));
        }

        @Override
        public @Nullable Component canPlayerLogin(SocketAddress socketAddress, GameProfile gameProfile) {
            var id = gameProfile.getId();
            var params = this.serviceParamsEntry.getValue();
            var internalAddr = this.serviceParamsEntry.getKey();
            try {
                GongdaobeiUtil.checkOwned(id, true, internalAddr, params, this.conn.get().sync());
                return super.canPlayerLogin(socketAddress, gameProfile);
            } catch (IOException e) {
                LOGGER.info("The player has been refused to connect to the server: {}", e.getMessage(), e);
                return DUPLICATE_LOGIN_DISCONNECT_MESSAGE;
            } catch (ExecutionException | InterruptedException e) {
                LOGGER.debug("The redis server is offline, no player data will be synced", e);
                return super.canPlayerLogin(socketAddress, gameProfile);
            }
        }

        @Override
        public void placeNewPlayer(Connection connection, ServerPlayer player, CommonListenerCookie cookie) {
            var id = player.getUUID();
            var entry = this.serviceParamsEntry;
            try {
                var cmd = this.conn.get().sync();
                var currentServer = this.getServer();
                var name = player.getGameProfile().getName();
                var holder = GongdaobeiUtil.tryLock(id, entry.getKey(), entry.getValue(), cmd);
                var targets = new ArrayList<GongdaobeiConfirmation>(entry.getValue().externalAddresses.size() + 2);
                var hitTargets = new LinkedHashSet<GongdaobeiConfirmation>();
                if (entry.getValue().isFallback) {
                    targets.add(new GongdaobeiConfirmation.Fallback(entry.getKey()));
                }
                for (var externalAddr : entry.getValue().externalAddresses) {
                    targets.add(new GongdaobeiConfirmation.Targeted(entry.getKey(), externalAddr));
                }
                targets.add(holder);
                var playerPair = GongdaobeiUtil.loadPlayerData(id, targets, cmd);
                if (playerPair.isPresent()) {
                    hitTargets.add(playerPair.get().getKey());
                    // TODO: custom player data files
                    var playerData = playerPair.get().getValue();
                    var dataDir = this.playerIo.getPlayerDir().toPath();
                    var tmp = Files.write(Files.createTempFile(dataDir, id + "-", ".dat"), playerData);
                    Util.safeReplaceFile(dataDir.resolve(id + ".dat"), tmp, dataDir.resolve(id + ".dat_old"));
                }
                var statsPair = GongdaobeiUtil.loadStats(id, targets, cmd);
                // noinspection DuplicatedCode
                if (statsPair.isPresent()) {
                    hitTargets.add(statsPair.get().getKey());
                    var statsData = statsPair.get().getValue();
                    var statsDir = Files.createDirectories(currentServer.getWorldPath(PLAYER_STATS_DIR));
                    var tmp = Files.write(Files.createTempFile(statsDir, id + "-", ".json"), statsData);
                    Files.move(tmp, statsDir.resolve(id + ".json"), StandardCopyOption.REPLACE_EXISTING);
                }
                var advancementsPair = GongdaobeiUtil.loadAdvancements(id, targets, cmd);
                // noinspection DuplicatedCode
                if (advancementsPair.isPresent()) {
                    hitTargets.add(advancementsPair.get().getKey());
                    var advancementsData = advancementsPair.get().getValue();
                    var advancementsDir = Files.createDirectories(currentServer.getWorldPath(PLAYER_ADVANCEMENTS_DIR));
                    var tmp = Files.write(Files.createTempFile(advancementsDir, id + "-", ".json"), advancementsData);
                    Files.move(tmp, advancementsDir.resolve(id + ".json"), StandardCopyOption.REPLACE_EXISTING);
                }
                if (hitTargets.isEmpty()) {
                    LOGGER.info("No player data / stats / advancements found for {} ({})", name, id);
                } else {
                    LOGGER.info("Synced player data / stats / advancements of {} ({}) from {}", name, id, hitTargets);
                }
                super.placeNewPlayer(connection, player, cookie);
            } catch (IOException e) {
                LOGGER.warn(e.getMessage(), e);
                connection.disconnect(DUPLICATE_LOGIN_DISCONNECT_MESSAGE);
            } catch (ExecutionException | InterruptedException e) {
                LOGGER.debug("The redis server is offline, no player data will be synced", e);
            }
        }

        @Override
        protected void save(ServerPlayer player) {
            super.save(player);
            var id = player.getUUID();
            var entry = this.serviceParamsEntry;
            try {
                var cmd = this.conn.get().sync();
                // TODO: custom player data files
                GongdaobeiUtil.checkOwned(id, false, entry.getKey(), entry.getValue(), cmd);
                var targets = new ArrayList<GongdaobeiConfirmation>(entry.getValue().externalAddresses.size() + 1);
                if (entry.getValue().isFallback) {
                    targets.add(new GongdaobeiConfirmation.Fallback(entry.getKey()));
                }
                for (var externalAddr : entry.getValue().externalAddresses) {
                    targets.add(new GongdaobeiConfirmation.Targeted(entry.getKey(), externalAddr));
                }
                var currentServer = this.getServer();
                var name = player.getGameProfile().getName();
                var dataDir = this.playerIo.getPlayerDir().toPath();
                var playerData = Files.readAllBytes(dataDir.resolve(id + ".dat"));
                GongdaobeiUtil.savePlayerData(id, playerData, targets, cmd);
                var statsDir = currentServer.getWorldPath(PLAYER_STATS_DIR);
                var stats = Files.readAllBytes(statsDir.resolve(id + ".json"));
                GongdaobeiUtil.saveStats(id, stats, targets, cmd);
                var advancementsDir = currentServer.getWorldPath(PLAYER_ADVANCEMENTS_DIR);
                var advancements = Files.readAllBytes(advancementsDir.resolve(id + ".json"));
                GongdaobeiUtil.saveAdvancements(id, advancements, targets, cmd);
                LOGGER.info("Synced player data / stats / advancements of {} ({}) to {}", name, id, targets);
            } catch (IOException e) {
                LOGGER.warn(e.getMessage(), e);
                player.connection.disconnect(DUPLICATE_LOGIN_DISCONNECT_MESSAGE);
            } catch (ExecutionException | InterruptedException e) {
                LOGGER.debug("The redis server is offline, no player data will be synced", e);
            }
        }

        @Override
        public void remove(ServerPlayer player) {
            super.remove(player);
            var id = player.getUUID();
            var entry = this.serviceParamsEntry;
            try {
                var cmd = this.conn.get().sync();
                GongdaobeiUtil.tryRelease(id, entry.getKey(), entry.getValue(), cmd);
            } catch (IOException e) {
                LOGGER.warn(e.getMessage(), e);
                player.connection.disconnect(DUPLICATE_LOGIN_DISCONNECT_MESSAGE);
            } catch (ExecutionException | InterruptedException e) {
                LOGGER.debug("The redis server is offline, no player data will be synced", e);
            }
        }

        @Override
        public void tick() {
            super.tick();
            var entry = this.serviceParamsEntry;
            var count = this.getServer().getTickCount();
            for (var player : this.getPlayers()) {
                var id = player.getUUID();
                if ((id.hashCode() + count - 1) % 250 == 0) {
                    // noinspection resource
                    Util.ioPool().submit(() -> {
                        try {
                            var cmd = this.conn.get().sync();
                            GongdaobeiUtil.tryRefreshLock(player.getUUID(), entry.getKey(), entry.getValue(), cmd);
                        } catch (IOException e) {
                            player.connection.disconnect(DUPLICATE_LOGIN_DISCONNECT_MESSAGE);
                        } catch (ExecutionException | InterruptedException e) {
                            LOGGER.debug("The redis server is offline, no player data will be synced", e);
                        }
                    });
                }
            }
            this.tickSubmitService(count);
        }

        public void tickSubmitService(int tickCount) {
            try {
                if ((tickCount - 1) % 20 == 19) {
                    var cmd = this.conn.get().sync();
                    var entry = this.serviceParamsEntry = this.refreshParamsEntry(tickCount, false);
                    // noinspection resource
                    Util.ioPool().submit(() -> GongdaobeiUtil.submitService(entry.getKey(), entry.getValue(), cmd));
                }
            } catch (ExecutionException | InterruptedException e) {
                LOGGER.debug("The redis server is offline, no service data will be submitted", e);
            }
        }

        @Override
        public void close() {
            try {
                var cmd = this.conn.get().sync();
                var count = this.getServer().getTickCount();
                var entry = this.serviceParamsEntry = this.refreshParamsEntry(count, true);
                CompletableFuture.runAsync(() -> GongdaobeiUtil.submitService(
                        entry.getKey(), entry.getValue(), cmd), Util.ioPool()).thenRun(this.redisClient::close);
            } catch (ExecutionException | InterruptedException e) {
                // eat it
                LOGGER.debug("The redis server is offline, no service data will be submitted", e);
            }
        }
    }
}
