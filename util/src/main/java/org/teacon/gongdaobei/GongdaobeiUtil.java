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

import com.google.common.net.HostAndPort;
import io.lettuce.core.*;
import io.lettuce.core.api.sync.RedisCommands;
import org.apache.commons.lang3.tuple.Pair;

import java.io.IOException;
import java.time.Duration;
import java.util.*;
import java.util.function.ToLongFunction;
import java.util.stream.Collectors;

import static com.google.common.base.Preconditions.checkArgument;

public final class GongdaobeiUtil {
    private static final Duration SERVICE_SUBMIT_EXPIRATION = Duration.ofMillis(5000);
    private static final Duration PLAYER_LOCK_EXPIRATION = Duration.ofMillis(62500);
    private static final String GZIP_DATA_PREFIX = "data:application/gzip;base64,";
    private static final String JSON_DATA_PREFIX = "data:application/json;base64,";
    private static final String GZIP_BASE64_HEADER = "H4sI";

    public static String desensitizeRedisUri(RedisURI uri) {
        var builder = uri.getSocket() != null ? RedisURI.Builder.socket(uri.getSocket())
                : uri.getHost() != null ? RedisURI.Builder.redis(uri.getHost(), uri.getPort()) : RedisURI.builder();

        builder.withSsl(uri);
        builder.withTimeout(uri.getTimeout());
        builder.withDatabase(uri.getDatabase());

        if (uri.getClientName() != null) {
            builder.withClientName(uri.getClientName());
        }

        return builder.build().toURI().toString();
    }

    public static ClientOptions getRedisClientOptions() {
        return ClientOptions.builder().publishOnScheduler(true).build();
    }

    public static Optional<GongdaobeiConfirmation> fetchAffinity(UUID player,
                                                                 RedisCommands<String, String> cmd) {
        var affinityKey = "gongdaobei:affinity{" + player + "}";
        return Optional.ofNullable(cmd.get(affinityKey)).flatMap(GongdaobeiConfirmation::tryParse);
    }

    public static void refreshAffinity(UUID player,
                                       ToLongFunction<GongdaobeiConfirmation> toAffinityMillis,
                                       RedisCommands<String, String> cmd) {
        var confirmationKey = "gongdaobei:confirmation{" + player + "}";
        var affinityKey = "gongdaobei:affinity{" + player + "}";
        while (true) {
            cmd.watch(confirmationKey, affinityKey);
            var confirmation = Optional.ofNullable(cmd.get(confirmationKey)).flatMap(GongdaobeiConfirmation::tryParse);
            cmd.multi();
            if (confirmation.isPresent()) {
                var timeout = toAffinityMillis.applyAsLong(confirmation.get());
                if (timeout > 0) {
                    cmd.set(affinityKey, confirmation.get().toString(), SetArgs.Builder.px(timeout));
                } else {
                    cmd.del(affinityKey);
                }
            } else {
                cmd.del(affinityKey);
            }
            var result = cmd.exec();
            if (!result.wasDiscarded()) {
                return;
            }
        }
    }

    public static void confirmPlayer(UUID player,
                                     GongdaobeiConfirmation target,
                                     RedisCommands<String, String> cmd) {
        cmd.set("gongdaobei:confirmation{" + player + "}", target.toString());
    }

    public static Optional<Pair<GongdaobeiConfirmation, byte[]>> loadStats(UUID player,
                                                                           List<GongdaobeiConfirmation> targets,
                                                                           RedisCommands<String, String> cmd) {
        var dataKey = "gongdaobei:player:stats{" + player + "}";
        // noinspection DuplicatedCode
        var list = cmd.hmget(dataKey, targets.stream().map(GongdaobeiConfirmation::toPlayerKey).toArray(String[]::new));
        for (var i = list.size() - 1; i >= 0; i--) {
            if (list.get(i).hasValue() && list.get(i).getValue().startsWith(JSON_DATA_PREFIX)) {
                var dataBytes = Base64.getDecoder().decode(list.get(i).getValue().substring(JSON_DATA_PREFIX.length()));
                return Optional.of(Pair.of(targets.get(i), dataBytes));
            }
        }
        return Optional.empty();
    }

    public static void saveStats(UUID player,
                                 byte[] dataBytes,
                                 List<GongdaobeiConfirmation> targets,
                                 RedisCommands<String, String> cmd) {
        var dataKey = "gongdaobei:player:stats{" + player + "}";
        var data = JSON_DATA_PREFIX + Base64.getEncoder().encodeToString(dataBytes);
        cmd.hset(dataKey, targets.stream().collect(Collectors.toMap(GongdaobeiConfirmation::toPlayerKey, k -> data)));
    }

    public static Optional<Pair<GongdaobeiConfirmation, byte[]>> loadAdvancements(UUID player,
                                                                                  List<GongdaobeiConfirmation> targets,
                                                                                  RedisCommands<String, String> cmd) {
        var dataKey = "gongdaobei:player:advancements{" + player + "}";
        // noinspection DuplicatedCode
        var list = cmd.hmget(dataKey, targets.stream().map(GongdaobeiConfirmation::toPlayerKey).toArray(String[]::new));
        for (var i = list.size() - 1; i >= 0; i--) {
            if (list.get(i).hasValue() && list.get(i).getValue().startsWith(JSON_DATA_PREFIX)) {
                var dataBytes = Base64.getDecoder().decode(list.get(i).getValue().substring(JSON_DATA_PREFIX.length()));
                return Optional.of(Pair.of(targets.get(i), dataBytes));
            }
        }
        return Optional.empty();
    }

    public static void saveAdvancements(UUID player,
                                        byte[] dataBytes,
                                        List<GongdaobeiConfirmation> targets,
                                        RedisCommands<String, String> cmd) {
        var dataKey = "gongdaobei:player:advancements{" + player + "}";
        var data = JSON_DATA_PREFIX + Base64.getEncoder().encodeToString(dataBytes);
        cmd.hset(dataKey, targets.stream().collect(Collectors.toMap(GongdaobeiConfirmation::toPlayerKey, k -> data)));
    }

    public static Optional<Pair<GongdaobeiConfirmation, byte[]>> loadPlayerData(UUID player,
                                                                                List<GongdaobeiConfirmation> targets,
                                                                                RedisCommands<String, String> cmd) {
        var dataKey = "gongdaobei:player:data{" + player + "}";
        var list = cmd.hmget(dataKey, targets.stream().map(GongdaobeiConfirmation::toPlayerKey).toArray(String[]::new));
        for (var i = list.size() - 1; i >= 0; i--) {
            if (list.get(i).hasValue() && list.get(i).getValue().startsWith(GZIP_DATA_PREFIX + GZIP_BASE64_HEADER)) {
                var dataBytes = Base64.getDecoder().decode(list.get(i).getValue().substring(GZIP_DATA_PREFIX.length()));
                return Optional.of(Pair.of(targets.get(i), dataBytes));
            }
        }
        return Optional.empty();
    }

    public static void savePlayerData(UUID player,
                                      byte[] dataBytes,
                                      List<GongdaobeiConfirmation> targets,
                                      RedisCommands<String, String> cmd) {
        var dataKey = "gongdaobei:player:data{" + player + "}";
        var data = GZIP_DATA_PREFIX + Base64.getEncoder().encodeToString(dataBytes);
        checkArgument(data.startsWith(GZIP_DATA_PREFIX + GZIP_BASE64_HEADER));
        cmd.hset(dataKey, targets.stream().collect(Collectors.toMap(GongdaobeiConfirmation::toPlayerKey, k -> data)));
    }

    public static void checkOwned(UUID player,
                                  boolean allowNoOwner,
                                  HostAndPort internalAddr,
                                  GongdaobeiServiceParams params,
                                  RedisCommands<String, String> cmd) throws IOException {
        var lockKey = "gongdaobei:player:lock{" + player + "}";
        var lock = Optional.ofNullable(cmd.get(lockKey)).flatMap(GongdaobeiConfirmation::tryParse);
        if (lock.isEmpty() && !allowNoOwner) {
            throw new IOException("player not connected to current server");
        }
        if (lock.isPresent() && !(lock.get().internalAddress().equals(internalAddr) && lock.get().test(params))) {
            throw new IOException("player already locked by another server");
        }
    }

    public static GongdaobeiConfirmation tryLock(UUID player,
                                                 HostAndPort internalAddr,
                                                 GongdaobeiServiceParams params,
                                                 RedisCommands<String, String> cmd) throws IOException {
        var lockKey = "gongdaobei:player:lock{" + player + "}";
        var confirmationKey = "gongdaobei:confirmation{" + player + "}";
        while (true) {
            cmd.watch(lockKey, confirmationKey);
            var confirmation = Optional.ofNullable(cmd.get(confirmationKey)).flatMap(GongdaobeiConfirmation::tryParse);
            var lock = Optional.ofNullable(cmd.get(lockKey)).flatMap(GongdaobeiConfirmation::tryParse);
            if (confirmation.isEmpty()) {
                cmd.unwatch();
                throw new IOException("player not connected to current server");
            }
            if (confirmation.equals(lock)) {
                cmd.unwatch();
                return confirmation.get();
            }
            if (!confirmation.get().internalAddress().equals(internalAddr) || !confirmation.get().test(params)) {
                cmd.unwatch();
                throw new IOException("player already locked by another server");
            }
            cmd.multi();
            cmd.set(lockKey, confirmation.get().toString(), SetArgs.Builder.nx().px(PLAYER_LOCK_EXPIRATION));
            var result = cmd.exec();
            if (!result.wasDiscarded()) {
                var setResult = result.<String>get(0);
                if (!"OK".equals(setResult)) {
                    throw new IOException("player not connected to current server");
                }
                return confirmation.get();
            }
        }
    }

    public static void tryRelease(UUID player,
                                  HostAndPort internalAddr,
                                  GongdaobeiServiceParams params,
                                  RedisCommands<String, String> cmd) throws IOException {
        var lockKey = "gongdaobei:player:lock{" + player + "}";
        while (true) {
            cmd.watch(lockKey);
            var lock = Optional.ofNullable(cmd.get(lockKey)).flatMap(GongdaobeiConfirmation::tryParse);
            if (lock.isEmpty()) {
                cmd.unwatch();
                throw new IOException("player not connected to current server");
            }
            if (!lock.get().internalAddress().equals(internalAddr) || !lock.get().test(params)) {
                cmd.unwatch();
                throw new IOException("player already locked by another server");
            }
            cmd.multi();
            cmd.del(lockKey);
            var result = cmd.exec();
            if (!result.wasDiscarded()) {
                return;
            }
        }
    }

    public static void tryRefreshLock(UUID player,
                                      HostAndPort internalAddr,
                                      GongdaobeiServiceParams params,
                                      RedisCommands<String, String> cmd) throws IOException {
        var lockKey = "gongdaobei:player:lock{" + player + "}";
        if (cmd.pexpire(lockKey, PLAYER_LOCK_EXPIRATION)) {
            var lock = Optional.ofNullable(cmd.get(lockKey)).flatMap(GongdaobeiConfirmation::tryParse);
            if (lock.isPresent() && lock.get().internalAddress().equals(internalAddr) && lock.get().test(params)) {
                return;
            }
        }
        throw new IOException("player not connected to current server");
    }

    public static void buildRegistry(GongdaobeiRegistry.Builder builder,
                                     Set<HostAndPort> externalAddrSet,
                                     RedisCommands<String, String> cmd) {
        var scanned = ScanIterator.scan(cmd, new ScanArgs().match("gongdaobei:service:*"));
        while (scanned.hasNext()) {
            var key = scanned.next();
            var addr = GongdaobeiUtil.getHostAndPort(key, "gongdaobei:service:", true);
            addr.ifPresent(h -> builder.params(h, GongdaobeiServiceParams.fromParams(cmd.hgetall(key))));
        }
        externalAddrSet.forEach(builder::whitelist);
    }

    public static void submitService(HostAndPort internalAddr,
                                     GongdaobeiServiceParams params,
                                     RedisCommands<String, String> cmd) {
        var serviceKey = "gongdaobei:service:" + internalAddr;
        cmd.hset(serviceKey, params.toParams());
        cmd.pexpire(serviceKey, SERVICE_SUBMIT_EXPIRATION);
    }

    public static Optional<HostAndPort> getHostAndPort(String input,
                                                       String prefix,
                                                       boolean checkPort) {
        try {
            checkArgument(input.startsWith(prefix));
            var addr = HostAndPort.fromString(input.substring(prefix.length())).requireBracketsForIPv6();
            checkArgument(addr.hasPort() || !checkPort);
            return Optional.of(addr);
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
