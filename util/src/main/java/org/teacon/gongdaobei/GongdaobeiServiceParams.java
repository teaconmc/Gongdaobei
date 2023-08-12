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
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import org.apache.commons.text.lookup.StringLookupFactory;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public final class GongdaobeiServiceParams {
    private static final Gson GSON = new GsonBuilder().create();

    public final String hostname;
    public final boolean isFallback;
    public final List<HostAndPort> externalAddresses;
    public final String motd;
    public final GongdaobeiTomlConfig.VersionPattern version;
    public final long affinityMillis;
    public final boolean isRetired;
    public final double tickMillis;
    public final int onlinePlayers;
    public final int maximumPlayers;
    public final JsonElement pingForgeData;

    private GongdaobeiServiceParams(Map<String, String> serviceParams) {
        this.hostname = serviceParams.getOrDefault("config:hostname", "default");
        this.isFallback = Boolean.parseBoolean(serviceParams.getOrDefault("config:fallback", "false"));
        this.externalAddresses = List.of(Arrays
                .stream(serviceParams.getOrDefault("config:external", "").stripLeading().split("\\s+"))
                .flatMap(s -> GongdaobeiUtil.getHostAndPort(s, "", false).stream()).toArray(HostAndPort[]::new));
        this.motd = serviceParams.getOrDefault("config:motd", "");
        this.version = new GongdaobeiTomlConfig.VersionPattern(
                serviceParams.getOrDefault("config:version", ""), StringLookupFactory.INSTANCE.nullStringLookup());
        this.affinityMillis = Long.parseUnsignedLong(serviceParams.getOrDefault("config:affinity", "0"));
        this.isRetired = !Boolean.parseBoolean(serviceParams.getOrDefault("status:register", "false"));
        this.tickMillis = Math.max(0.0, Double.parseDouble(serviceParams.getOrDefault("status:tick", "0")));
        this.onlinePlayers = Integer.parseUnsignedInt(serviceParams.getOrDefault("status:online", "0"));
        this.maximumPlayers = Integer.parseUnsignedInt(serviceParams.getOrDefault("status:maximum", "0"));
        this.pingForgeData = GSON.fromJson(serviceParams.getOrDefault("status:forgedata", "{}"), JsonElement.class);
    }

    public GongdaobeiServiceParams(String hostname,
                                   GongdaobeiTomlConfig.Service config, boolean isServerRetired, String motd,
                                   double tickMillis, int onlinePlayers, int maximumPlayers, JsonElement pingForgeData) {
        this.hostname = hostname;
        this.isFallback = config.isFallbackServer();
        this.externalAddresses = List.of(config.externalAddresses()
                .stream().map(GongdaobeiTomlConfig.AddressPattern::getValue).toArray(HostAndPort[]::new));
        this.motd = motd;
        this.version = config.version().resolve();
        this.affinityMillis = config.affinityMillis();
        this.isRetired = isServerRetired;
        this.tickMillis = tickMillis;
        this.onlinePlayers = onlinePlayers;
        this.maximumPlayers = maximumPlayers;
        this.pingForgeData = pingForgeData;
    }

    public static GongdaobeiServiceParams fromParams(Map<String, String> serviceParams) {
        return new GongdaobeiServiceParams(serviceParams);
    }

    public Map<String, String> toParams() {
        return Map.ofEntries(
                Map.entry("config:hostname", this.hostname),
                Map.entry("config:fallback", Boolean.toString(this.isFallback)),
                Map.entry("config:external", this.externalAddresses
                        .stream().map(HostAndPort::toString).collect(Collectors.joining("\t"))),
                Map.entry("config:motd", this.motd),
                Map.entry("config:version", this.version.toString()),
                Map.entry("config:affinity", Long.toUnsignedString(this.affinityMillis)),
                Map.entry("status:register", Boolean.toString(!this.isRetired)),
                Map.entry("status:tick", String.format("%.8f", this.tickMillis)),
                Map.entry("status:online", Integer.toUnsignedString(this.onlinePlayers)),
                Map.entry("status:maximum", Integer.toUnsignedString(this.maximumPlayers)),
                Map.entry("status:forgedata", GSON.toJson(this.pingForgeData)));
    }
}
