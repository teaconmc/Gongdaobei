package org.teacon.gongdaobei;

import com.google.common.base.Preconditions;
import com.google.common.net.HostAndPort;
import io.lettuce.core.ClientOptions;
import io.lettuce.core.ScanArgs;
import io.lettuce.core.ScanIterator;
import io.lettuce.core.api.StatefulRedisConnection;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public final class GongdaobeiUtil {
    public static ClientOptions getRedisClientOptions() {
        return ClientOptions.builder().publishOnScheduler(true).build();
    }

    public static Optional<HostAndPort> getAffinityTarget(
            UUID playerUniqueId, CompletableFuture<? extends StatefulRedisConnection<String, String>> conn) {
        var targetString = conn.join().sync().get("gongdaobei:affinity:" + playerUniqueId);
        return Optional.ofNullable(targetString).flatMap(input -> getHostAndPort(input, ""));
    }

    public static void setAffinityTarget(
            UUID playerUniqueId, HostAndPort target,
            CompletableFuture<? extends StatefulRedisConnection<String, String>> conn, long affinityMillis) {
        conn.join().sync().psetex("gongdaobei:affinity:" + playerUniqueId, affinityMillis, target.toString());
    }

    public static Map<HostAndPort, GongdaobeiServiceParams> getServiceParams(
            CompletableFuture<? extends StatefulRedisConnection<String, String>> conn) {
        var commands = conn.join().sync();
        var params = new HashMap<HostAndPort, GongdaobeiServiceParams>();
        var scanned = ScanIterator.scan(commands, new ScanArgs().match("gongdaobei:service:*"));
        while (scanned.hasNext()) {
            var key = scanned.next();
            var addr = GongdaobeiUtil.getHostAndPort(key, "gongdaobei:service:");
            addr.ifPresent(h -> params.put(h, GongdaobeiServiceParams.fromParams(commands.hgetall(key))));
        }
        return Map.copyOf(params);
    }

    public static void setServiceParams(
            Map.Entry<HostAndPort, GongdaobeiServiceParams> params,
            CompletableFuture<? extends StatefulRedisConnection<String, String>> conn) {
        var key = "gongdaobei:service:" + params.getKey().toString();
        var commands = conn.join().sync();
        commands.hset(key, params.getValue().toParams());
        commands.pexpire(key, 5000L);
    }

    public static Optional<HostAndPort> getHostAndPort(String input, String prefix) {
        try {
            Preconditions.checkArgument(input.startsWith(prefix));
            var addr = HostAndPort.fromString(input.substring(prefix.length()));
            Preconditions.checkArgument(addr.requireBracketsForIPv6().hasPort());
            return Optional.of(addr);
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
