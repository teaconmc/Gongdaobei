package org.teacon.gongdaobei;

import com.mojang.logging.LogUtils;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.codec.StringCodec;
import io.lettuce.core.masterreplica.MasterReplica;
import net.minecraft.Util;
import net.minecraft.server.dedicated.DedicatedServer;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.server.ServerStartingEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.fml.IExtensionPoint;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.loading.FMLPaths;
import net.minecraftforge.network.NetworkConstants;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.slf4j.Logger;

import java.io.Closeable;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

@Mod(GongdaobeiForge.ID)
public final class GongdaobeiForge {
    public static final String ID = "gongdaobei";
    private static final Logger LOGGER = LogUtils.getLogger();

    private GongdaobeiTomlConfig.Service config;
    private Handler handler;

    public GongdaobeiForge() {
        ModLoadingContext.get().registerExtensionPoint(IExtensionPoint.DisplayTest.class,
                () -> new IExtensionPoint.DisplayTest(() -> NetworkConstants.IGNORESERVERONLY, (v, s) -> true));
        MinecraftForge.EVENT_BUS.addListener(this::onStarting);
        MinecraftForge.EVENT_BUS.addListener(this::onStopping);
    }

    private void onStarting(ServerStartingEvent event) {
        if (event.getServer() instanceof DedicatedServer server) {
            LOGGER.info("Loading from the configuration file ...");
            var confFile = FMLPaths.CONFIGDIR.get().resolve(Path.of("gongdaobei.toml"));
            this.config = GongdaobeiTomlConfig.Service.load(confFile).save(confFile);
            LOGGER.info("- Discovery Redis URI: {} (resolved from {})",
                    this.config.discoveryRedisUri().getValue().toURI(), this.config.discoveryRedisUri().getKey());
            LOGGER.info("- Internal Address: {} (resolved from {})",
                    this.config.internalAddress().getValue(), this.config.internalAddress().getKey());
            LOGGER.info("- External Addresses: {} (resolved from {})",
                    this.config.externalAddresses().stream().map(Pair::getValue).toList(), this.config.externalAddresses().stream().map(Pair::getKey).toList());
            LOGGER.info("- Is Fallback Server: {}",
                    this.config.isFallbackServer() ? "TRUE" : "FALSE");
            LOGGER.info("- Version: {} (resolved from {})",
                    StringUtils.defaultIfEmpty(this.config.version().resolve().toString(), "undefined"), this.config.version());
            LOGGER.info("- Affinity Millis: {}",
                    this.config.affinityMillis());
            this.handler = new Handler(server, this.config);
        }
    }

    private void onStopping(ServerStoppingEvent event) {
        if (event.getServer() instanceof DedicatedServer) {
            LOGGER.info("Saving to the configuration file ...");
            var confFile = FMLPaths.CONFIGDIR.get().resolve(Path.of("gongdaobei.toml"));
            this.config.save(confFile);
            this.handler.close();
        }
    }

    private static class Handler implements Runnable, Closeable {
        private final RedisClient redisClient;
        private final CompletableFuture<? extends StatefulRedisConnection<String, String>> conn;
        private final DedicatedServer server;
        private final GongdaobeiTomlConfig.Service config;

        public Handler(DedicatedServer server, GongdaobeiTomlConfig.Service config) {
            this.redisClient = Util.make(
                    RedisClient.create(), c -> c.setOptions(GongdaobeiUtil.getRedisClientOptions()));
            this.conn = MasterReplica.connectAsync(
                    this.redisClient, StringCodec.UTF8, config.discoveryRedisUri().getValue()).whenComplete((c, e) -> {
                var uri = config.discoveryRedisUri().getValue().toURI();
                if (c != null) {
                    LOGGER.info("Connected to the discovery redis server ({})", uri);
                }
                if (e != null) {
                    LOGGER.error("Failed to connect to the discovery redis server ({}), " +
                            "the server will run on offline mode and will not handle anything", uri, e);
                }
            });
            this.server = Util.make(server, s -> s.addTickable(this));
            this.config = config;
        }

        private double twentyTicksAvgMillis(long[] tickTimeNanos, int tickCount) {
            var twentyTicksSumNanos = 0L;
            var tickTimeCount = tickTimeNanos.length;
            for (var i = tickCount - 20; i < tickCount; ++i) {
                twentyTicksSumNanos += tickTimeNanos[i % tickTimeCount];
            }
            return twentyTicksSumNanos * (0.000001 / 20);
        }

        @Override
        public void run() {
            var count = this.server.getTickCount();
            if ((count - 1) % 20 == 19) {
                var params = Map.entry(
                        this.config.internalAddress().getValue().withDefaultPort(this.server.getPort()),
                        new GongdaobeiServiceParams(
                                this.config, false, this.server.getMotd(),
                                this.twentyTicksAvgMillis(this.server.tickTimes, count),
                                this.server.getPlayerCount(), this.server.getMaxPlayers()));
                CompletableFuture.runAsync(() -> GongdaobeiUtil.setServiceParams(params, this.conn), Util.ioPool());
            }
        }

        @Override
        public void close() {
            var count = this.server.getTickCount();
            var params = Map.entry(
                    this.config.internalAddress().getValue().withDefaultPort(this.server.getPort()),
                    new GongdaobeiServiceParams(
                            this.config, true, this.server.getMotd(),
                            this.twentyTicksAvgMillis(this.server.tickTimes, count),
                            this.server.getPlayerCount(), this.server.getMaxPlayers()));
            var future = CompletableFuture.runAsync(() -> GongdaobeiUtil.setServiceParams(params, this.conn), Util.ioPool());
            future.thenRun(this.redisClient::close);
        }
    }
}
