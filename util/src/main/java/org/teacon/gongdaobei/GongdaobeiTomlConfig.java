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

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;
import com.google.common.net.HostAndPort;
import com.moandjiezana.toml.Toml;
import com.moandjiezana.toml.TomlWriter;
import com.vdurmont.semver4j.Semver;
import com.vdurmont.semver4j.SemverException;
import io.lettuce.core.RedisURI;
import org.apache.commons.text.StringSubstitutor;
import org.apache.commons.text.lookup.StringLookup;
import org.apache.commons.text.lookup.StringLookupFactory;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Optional;

public final class GongdaobeiTomlConfig {
    private static final String DISCOVERY_REDIS = "discoveryRedisUri";
    private static final String PROM_SERVER_PORT = "prometheusServerPort";
    private static final String EXTERNAL_ADDRESS_WHITELIST = "externalAddressWhitelist";
    private static final String INTERNAL_ADDRESS = "internalAddress";
    private static final String EXTERNAL_ADDRESSES = "externalAddresses";
    private static final String IS_FALLBACK_SERVER = "isFallbackServer";
    private static final String VERSION = "version";
    private static final String AFFINITY_MILLIS = "affinityMillis";
    private static final String DEFAULT_DISCOVERY_REDIS = "${GONGDAOBEI_SERVICE_DISCOVERY:-redis://localhost:6379/0}";
    private static final long DEFAULT_PROM_SERVER_PORT = 0L;
    private static final List<String> DEFAULT_EXTERNAL_ADDRESS_WHITELIST = List.of();
    private static final String DEFAULT_INTERNAL_ADDRESS = "${GONGDAOBEI_SERVICE_INTERNAL:-localhost}";
    private static final List<String> DEFAULT_EXTERNAL_ADDRESSES = List.of();
    private static final boolean DEFAULT_IS_FALLBACK_SERVER = true;
    private static final String DEFAULT_VERSION = "${GONGDAOBEI_SERVICE_VERSION:-1.0.0}";
    private static final long DEFAULT_AFFINITY_MILLIS = 1200000L;

    public static final class RedisLocationPattern {
        private final RedisURI object;
        private final String pattern;

        public RedisLocationPattern(String pattern) {
            this.object = RedisURI.create(pattern);
            this.pattern = pattern;
        }

        public RedisURI getValue() {
            return this.object;
        }

        @Override
        public String toString() {
            return this.pattern;
        }
    }

    public static final class AddressPattern {
        private final HostAndPort object;
        private final String pattern;

        public AddressPattern(String pattern) {
            this.object = GongdaobeiUtil.getHostAndPortUnchecked(pattern);
            this.pattern = pattern;
        }

        public HostAndPort getValue() {
            return this.object;
        }

        @Override
        public String toString() {
            return this.pattern;
        }
    }

    public static final class VersionPattern implements Comparable<VersionPattern> {
        private final @Nullable Semver semver;
        private final String pattern;

        public VersionPattern(String pattern, StringLookup lookup) {
            var versionString = new StringSubstitutor(lookup).replace(pattern).strip();
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
            var lookup = StringLookupFactory.INSTANCE.nullStringLookup();
            return new VersionPattern(this.semver == null ? "" : this.semver.toString(), lookup);
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

    public record Bungee(RedisLocationPattern discoveryRedisUri,
                         int prometheusServerPort,
                         List<AddressPattern> externalAddresses) {
        public Bungee save(Path configFile) {
            try (var output = Files.newBufferedWriter(configFile, StandardOpenOption.CREATE)) {
                var toml = new TomlWriter.Builder().indentTablesBy(0).indentValuesBy(0).build();
                var common = ImmutableMap.of(
                        DISCOVERY_REDIS, this.discoveryRedisUri.toString());
                var bungee = ImmutableMap.of(
                        PROM_SERVER_PORT, this.prometheusServerPort,
                        EXTERNAL_ADDRESS_WHITELIST, Lists.transform(this.externalAddresses, AddressPattern::toString));
                toml.write(ImmutableMap.of("common", common, "bungee", bungee), output);
                return this;
            } catch (IOException | ClassCastException e) {
                throw new IllegalArgumentException(e);
            }
        }

        public static Bungee load(Path configFile) {
            try (var input = Files.exists(configFile) ? Files.newBufferedReader(configFile) : Reader.nullReader()) {
                var toml = new Toml().read(input);
                var common = Optional.ofNullable(toml.getTable("common"));
                var bungee = Optional.ofNullable(toml.getTable("bungee"));
                var discoveryRedisUri = common
                        .map(t -> t.getString(DISCOVERY_REDIS)).orElse(DEFAULT_DISCOVERY_REDIS).strip();
                var prometheusServerPort = bungee
                        .map(t -> t.getLong(PROM_SERVER_PORT)).orElse(DEFAULT_PROM_SERVER_PORT);
                var externalAddresses = bungee
                        .map(t -> t.<String>getList(EXTERNAL_ADDRESS_WHITELIST)).orElse(DEFAULT_EXTERNAL_ADDRESS_WHITELIST);
                return new Bungee(
                        new RedisLocationPattern(discoveryRedisUri),
                        Math.toIntExact(Math.min(65535L, Math.max(0L, prometheusServerPort))),
                        List.of(externalAddresses.stream().map(AddressPattern::new).toArray(AddressPattern[]::new)));
            } catch (IOException | ClassCastException e) {
                throw new IllegalArgumentException(e);
            }
        }
    }

    public record Service(RedisLocationPattern discoveryRedisUri,
                          AddressPattern internalAddress,
                          List<AddressPattern> externalAddresses,
                          boolean isFallbackServer,
                          VersionPattern version,
                          long affinityMillis) {
        public Service save(Path configFile) {
            try (var output = Files.newBufferedWriter(configFile, StandardOpenOption.CREATE)) {
                var toml = new TomlWriter.Builder().indentTablesBy(0).indentValuesBy(0).build();
                var common = ImmutableMap.of(
                        DISCOVERY_REDIS, this.discoveryRedisUri.toString());
                var service = ImmutableMap.of(
                        INTERNAL_ADDRESS, this.internalAddress.toString(),
                        EXTERNAL_ADDRESSES, Lists.transform(this.externalAddresses, AddressPattern::toString),
                        IS_FALLBACK_SERVER, this.isFallbackServer,
                        VERSION, this.version.toString(),
                        AFFINITY_MILLIS, this.affinityMillis);
                toml.write(ImmutableMap.of("common", common, "service", service), output);
                return this;
            } catch (IOException | ClassCastException e) {
                throw new IllegalArgumentException(e);
            }
        }

        public static Service load(Path configFile) {
            try (var input = Files.exists(configFile) ? Files.newBufferedReader(configFile) : Reader.nullReader()) {
                var toml = new Toml().read(input);
                var common = Optional.ofNullable(toml.getTable("common"));
                var service = Optional.ofNullable(toml.getTable("service"));
                var discoveryRedisUri = common
                        .map(t -> t.getString(DISCOVERY_REDIS)).orElse(DEFAULT_DISCOVERY_REDIS).strip();
                var internalAddress = service
                        .map(t -> t.getString(INTERNAL_ADDRESS)).orElse(DEFAULT_INTERNAL_ADDRESS);
                var externalAddresses = service
                        .map(t -> t.<String>getList(EXTERNAL_ADDRESSES)).orElse(DEFAULT_EXTERNAL_ADDRESSES);
                var isFallbackServer = service
                        .map(t -> t.getBoolean(IS_FALLBACK_SERVER)).orElse(DEFAULT_IS_FALLBACK_SERVER);
                var version = service
                        .map(t -> t.getString(VERSION)).orElse(DEFAULT_VERSION);
                var affinityMillis = service
                        .map(t -> t.getLong(AFFINITY_MILLIS)).orElse(DEFAULT_AFFINITY_MILLIS);
                return new Service(
                        new RedisLocationPattern(discoveryRedisUri),
                        new AddressPattern(internalAddress),
                        List.of(externalAddresses.stream().map(AddressPattern::new).toArray(AddressPattern[]::new)),
                        isFallbackServer,
                        new VersionPattern(version, StringLookupFactory.INSTANCE.environmentVariableStringLookup()),
                        affinityMillis);
            } catch (IOException | ClassCastException | SemverException e) {
                throw new IllegalArgumentException(e);
            }
        }
    }
}
