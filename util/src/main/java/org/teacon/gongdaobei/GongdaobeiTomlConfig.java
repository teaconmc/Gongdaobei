package org.teacon.gongdaobei;

import com.google.common.collect.ImmutableMap;
import com.google.common.net.HostAndPort;
import com.moandjiezana.toml.Toml;
import com.moandjiezana.toml.TomlWriter;
import com.vdurmont.semver4j.Semver;
import com.vdurmont.semver4j.SemverException;
import io.lettuce.core.RedisURI;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

public final class GongdaobeiTomlConfig {
    private static final String DISCOVERY_REDIS_URI = "discoveryRedisUri";
    private static final String INTERNAL_ADDRESS = "internalAddress";
    private static final String EXTERNAL_ADDRESSES = "externalAddresses";
    private static final String IS_FALLBACK_SERVER = "isFallbackServer";
    private static final String VERSION = "version";
    private static final String AFFINITY_MILLIS = "affinityMillis";
    private static final String DEFAULT_DISCOVERY_REDIS_URI = "redis://localhost:6379/0";
    private static final String DEFAULT_INTERNAL_ADDRESS = "localhost";
    private static final List<String> DEFAULT_EXTERNAL_ADDRESSES = List.of();
    private static final boolean DEFAULT_IS_FALLBACK_SERVER = true;
    private static final String DEFAULT_VERSION = "${gongdaobei.service.version:-1.0.0}";
    private static final long DEFAULT_AFFINITY_MILLIS = 1200000L;

    public static final class VersionPattern implements Comparable<VersionPattern> {
        private final @Nullable Semver semver;
        private final String pattern;

        public VersionPattern(String pattern, Function<? super String, String> substitutor) {
            var versionString = substitutor.apply(pattern).strip();
            if (versionString.startsWith("v")) {
                versionString = versionString.substring(1);
            }
            this.semver = versionString.isEmpty() ? null : new Semver(versionString);
            this.pattern = pattern;
        }

        public VersionPattern() {
            this.semver = null;
            this.pattern = "";
        }

        public VersionPattern resolve() {
            return new VersionPattern(this.semver == null ? "" : this.semver.toString(), Function.identity());
        }

        @Override
        public String toString() {
            return this.pattern;
        }

        @Override
        public int compareTo(@Nonnull VersionPattern that) {
            if (this.semver == null || that.semver == null) {
                return this.semver != null ? 1 : that.semver != null ? -1 : 0;
            } else {
                return this.semver.compareTo(that.semver);
            }
        }
    }

    public record Common(RedisURI discoveryRedisUri) {
        public Common save(Path configFile) {
            try (var output = Files.newBufferedWriter(configFile, StandardOpenOption.CREATE)) {
                var toml = new TomlWriter.Builder().indentTablesBy(0).indentValuesBy(0).build();
                var common = ImmutableMap.of(DISCOVERY_REDIS_URI, this.discoveryRedisUri.toURI().toString());
                toml.write(ImmutableMap.of("common", common), output);
                return this;
            } catch (IOException | ClassCastException e) {
                throw new IllegalArgumentException(e);
            }
        }

        public static Common load(Path configFile) {
            try (var input = Files.exists(configFile) ? Files.newBufferedReader(configFile) : Reader.nullReader()) {
                var toml = new Toml().read(input);
                var common = Optional.ofNullable(toml.getTable("common"));
                var discoveryRedisUri = common.map(t -> t.getString(DISCOVERY_REDIS_URI)).orElse(DEFAULT_DISCOVERY_REDIS_URI);
                return new Common(RedisURI.create(discoveryRedisUri));
            } catch (IOException | ClassCastException e) {
                throw new IllegalArgumentException(e);
            }
        }
    }

    public record Service(RedisURI discoveryRedisUri,
                          HostAndPort internalAddress,
                          List<HostAndPort> externalAddresses,
                          boolean isFallbackServer,
                          VersionPattern version,
                          long affinityMillis) {
        public Service save(Path configFile) {
            try (var output = Files.newBufferedWriter(configFile, StandardOpenOption.CREATE)) {
                var toml = new TomlWriter.Builder().indentTablesBy(0).indentValuesBy(0).build();
                var common = ImmutableMap.of(DISCOVERY_REDIS_URI, this.discoveryRedisUri.toURI().toString());
                var service = ImmutableMap.of(
                        INTERNAL_ADDRESS, this.internalAddress.toString(),
                        EXTERNAL_ADDRESSES, this.externalAddresses.stream().map(HostAndPort::toString).toList(),
                        IS_FALLBACK_SERVER, this.isFallbackServer,
                        VERSION, this.version.toString(),
                        AFFINITY_MILLIS, this.affinityMillis);
                toml.write(ImmutableMap.of("common", common, "service", service), output);
                return this;
            } catch (IOException | ClassCastException e) {
                throw new IllegalArgumentException(e);
            }
        }

        public static Service load(Path configFile, Function<? super String, String> substitutor) {
            try (var input = Files.exists(configFile) ? Files.newBufferedReader(configFile) : Reader.nullReader()) {
                var toml = new Toml().read(input);
                var common = Optional.ofNullable(toml.getTable("common"));
                var service = Optional.ofNullable(toml.getTable("service"));
                var discoveryRedisUri = common.map(t -> t.getString(DISCOVERY_REDIS_URI)).orElse(DEFAULT_DISCOVERY_REDIS_URI);
                var internalAddress = service.map(t -> t.getString(INTERNAL_ADDRESS)).orElse(DEFAULT_INTERNAL_ADDRESS);
                var externalAddresses = service.map(t -> t.<String>getList(EXTERNAL_ADDRESSES)).orElse(DEFAULT_EXTERNAL_ADDRESSES);
                var isFallbackServer = service.map(t -> t.getBoolean(IS_FALLBACK_SERVER)).orElse(DEFAULT_IS_FALLBACK_SERVER);
                var version = service.map(t -> t.getString(VERSION)).orElse(DEFAULT_VERSION);
                var affinityMillis = service.map(t -> t.getLong(AFFINITY_MILLIS)).orElse(DEFAULT_AFFINITY_MILLIS);
                return new Service(RedisURI.create(discoveryRedisUri),
                        HostAndPort.fromString(internalAddress).requireBracketsForIPv6(),
                        List.of(externalAddresses.stream().map(HostAndPort::fromString)
                                .map(HostAndPort::requireBracketsForIPv6).toArray(HostAndPort[]::new)),
                        isFallbackServer, new VersionPattern(version, substitutor), affinityMillis);
            } catch (IOException | ClassCastException | SemverException e) {
                throw new IllegalArgumentException(e);
            }
        }
    }
}
