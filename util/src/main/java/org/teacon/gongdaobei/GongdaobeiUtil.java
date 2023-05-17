package org.teacon.gongdaobei;

import com.google.common.base.Preconditions;
import com.google.common.net.HostAndPort;
import com.vdurmont.semver4j.Semver;
import com.vdurmont.semver4j.SemverException;
import io.lettuce.core.ClientOptions;
import io.lettuce.core.ScanArgs;
import io.lettuce.core.ScanIterator;
import io.lettuce.core.api.sync.RedisCommands;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public final class GongdaobeiUtil {
    public static ClientOptions getRedisClientOptions() {
        return ClientOptions.builder().publishOnScheduler(true).build();
    }

    public static Optional<HostAndPort> getAffinityTarget(UUID playerUniqueId,
                                                          RedisCommands<String, String> commands) {
        var targetString = commands.get("gongdaobei:affinity:" + playerUniqueId);
        return Optional.ofNullable(targetString).flatMap(input -> getHostAndPort(input, ""));
    }

    public static void setAffinityTarget(UUID playerUniqueId, HostAndPort target,
                                         RedisCommands<String, String> commands, long affinityMillis) {
        commands.psetex("gongdaobei:affinity:" + playerUniqueId, affinityMillis, target.toString());
    }

    public static Map<HostAndPort, GongdaobeiServiceParams> getServiceParams(RedisCommands<String, String> commands) {
        var scanned = ScanIterator.scan(commands, new ScanArgs().match("gongdaobei:service:*"));
        var result = new HashMap<HostAndPort, GongdaobeiServiceParams>();
        while (scanned.hasNext()) {
            var key = scanned.next();
            var addr = GongdaobeiUtil.getHostAndPort(key, "gongdaobei:service:");
            addr.ifPresent(h -> result.put(h, GongdaobeiServiceParams.fromParams(commands.hgetall(key))));
        }
        return Map.copyOf(result);
    }

    public static void setServiceParams(RedisCommands<String, String> commands,
                                        Map.Entry<HostAndPort, GongdaobeiServiceParams> params) {
        var key = "gongdaobei:service:" + params.getKey().toString();
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

    public static Optional<Semver> getVersion(String input) {
        try {
            return Optional.of(new Semver(input));
        } catch (SemverException e) {
            return Optional.empty();
        }
    }
}
