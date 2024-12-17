package org.teacon.gongdaobei;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Sets;
import com.google.common.collect.TreeMultimap;
import com.google.common.net.HostAndPort;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import org.apache.commons.lang3.tuple.Pair;
import reactor.core.publisher.Mono;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Predicate;

public final class GongdaobeiVelocityCommand implements SimpleCommand {
    private static final MethodHandle GET_CONNECTED_SERVER;
    private static final MethodHandle SET_CONNECTED_SERVER;
    private static final MethodHandle DISCONNECT;
    private static final MethodHandle SEND_KEEP_ALIVE;

    static {
        try {
            var lookup = MethodHandles.lookup();
            var cp = Class.forName("com.velocitypowered.proxy.connection.client.ConnectedPlayer");
            var vsc = Class.forName("com.velocitypowered.proxy.connection.backend.VelocityServerConnection");
            GET_CONNECTED_SERVER = lookup.findVirtual(cp, "getConnectedServer", MethodType.methodType(vsc));
            SET_CONNECTED_SERVER = lookup.findVirtual(cp, "setConnectedServer", MethodType.methodType(void.class, vsc));
            DISCONNECT = lookup.findVirtual(vsc, "disconnect", MethodType.methodType(void.class));
            SEND_KEEP_ALIVE = lookup.findVirtual(cp, "sendKeepAlive", MethodType.methodType(void.class));
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private final ProxyServer proxyServer;
    private final AtomicReference<GongdaobeiRegistry> registry;
    private final Mono<StatefulRedisConnection<String, String>> conn;
    private final Function<GongdaobeiConfirmation, Optional<RegisteredServer>> toRegisteredServer;

    public GongdaobeiVelocityCommand(ProxyServer server,
                                     AtomicReference<GongdaobeiRegistry> registry,
                                     Mono<StatefulRedisConnection<String, String>> conn,
                                     Function<GongdaobeiConfirmation, Optional<RegisteredServer>> toServer) {
        this.conn = conn;
        this.registry = registry;
        this.proxyServer = server;
        this.toRegisteredServer = toServer;
    }

    private void disconnectVirtually(Player player, RedisCommands<String, String> cmd) {
        try {
            var existing = GET_CONNECTED_SERVER.invoke(player);
            if (existing != null) {
                SET_CONNECTED_SERVER.invoke(player, null);
                DISCONNECT.invoke(existing);
            }
            while (true) {
                SEND_KEEP_ALIVE.invoke(player);
                if (GongdaobeiUtil.checkNoOwner(player.getUniqueId(), cmd)) {
                    // the player lock has been released
                    return;
                }
                // otherwise spinning
            }
        } catch (Throwable t) {
            throw new IllegalStateException(t);
        }
    }

    @Override
    public void execute(Invocation invocation) {
        var source = invocation.source();
        if (source instanceof Player player) {
            // args and player id
            var arguments = invocation.arguments();
            var sourceUniqueId = player.getUniqueId();
            // collect player choices
            var playerAddr = player.getVirtualHost().orElse(null);
            var collected = new LinkedHashMap<GongdaobeiConfirmation, GongdaobeiServiceParams>();
            GongdaobeiConfirmation.collect(playerAddr, this.registry.get(), collected::put);
            // get player current confirmation
            var confirmation = this.conn.flatMap(c -> GongdaobeiUtil.fetchConfirmation(sourceUniqueId, c.reactive()));
            var next = switch (arguments.length) {
                // no argument: transfer to another server
                case 0 -> confirmation.mapNotNull(c -> {
                    var coll = TreeMultimap.create(
                            Comparator.comparing(HostAndPort::toString),
                            Comparator.comparing(GongdaobeiConfirmation::toString));
                    for (var collectedConfirmation : collected.keySet()) {
                        coll.put(collectedConfirmation.internalAddress(), collectedConfirmation);
                    }
                    var prev = coll.keySet().lower(c.internalAddress());
                    if (prev == null && !coll.isEmpty()) {
                        prev = coll.keySet().last();
                    }
                    return prev == null ? null : coll.get(prev).last();
                }).switchIfEmpty(Mono.fromRunnable(() -> {
                    var msg = "<lang:velocity.error.no-available-servers>";
                    player.sendRichMessage(msg);
                }));
                // one argument: transfer to the player
                case 1 -> {
                    var targetUniqueId = Optional.<UUID>empty();
                    try {
                        targetUniqueId = Optional.of(UUID.fromString(arguments[0]));
                    } catch (IllegalArgumentException e) {
                        targetUniqueId = this.proxyServer.getPlayer(arguments[0]).map(Player::getUniqueId);
                    }
                    var target = targetUniqueId.map(t -> this.conn.flatMap(
                            c -> GongdaobeiUtil.fetchConfirmation(t, c.reactive()))).orElse(Mono.empty());
                    yield confirmation.flatMap(c -> target.mapNotNull(t -> {
                        var coll = Sets.newTreeSet(Comparator.comparing(GongdaobeiConfirmation::toString));
                        for (var collectedConfirmation : collected.keySet()) {
                            if (collectedConfirmation.internalAddress().equals(t.internalAddress())) {
                                coll.add(collectedConfirmation);
                            }
                        }
                        return coll.isEmpty() ? null : coll.last();
                    })).switchIfEmpty(Mono.fromRunnable(() -> {
                        var msg = "<lang:argument.player.unknown>";
                        player.sendRichMessage(msg);
                    }));
                }
                // two or more arguments: error
                default -> Mono.<GongdaobeiConfirmation>fromRunnable(() -> {
                    var msg = "<lang:command.unknown.argument>";
                    player.sendRichMessage(msg);
                });
            };
            Mono.zip(next, this.conn, Pair::of).subscribe(pair -> {
                var target = pair.getKey();
                var server = this.toRegisteredServer.apply(target);
                if (server.isPresent()) {
                    GongdaobeiUtil.confirmPlayer(sourceUniqueId, target, pair.getValue().sync());
                    this.disconnectVirtually(player, pair.getValue().sync());
                    GongdaobeiVelocityPromMetrics.totalLogins.inc();
                    player.createConnectionRequest(server.get()).fireAndForget();
                } else {
                    var msg = "<lang:velocity.error.no-available-servers>";
                    player.sendRichMessage(msg);
                }
            });
        }
    }

    @Override
    public List<String> suggest(Invocation invocation) {
        var args = invocation.arguments();
        return args.length == 0 ? this.proxyServer.getAllPlayers()
                .stream().filter(Predicate.isEqual(invocation.source()).negate())
                .map(Player::getUsername).collect(ImmutableList.toImmutableList()) : ImmutableList.of();
    }
}
