package org.teacon.gongdaobei;

import com.google.common.net.HostAndPort;
import com.vdurmont.semver4j.Semver;
import org.apache.commons.lang3.StringUtils;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

public final class GongdaobeiServiceParams {
    public final boolean isFallback;
    public final List<HostAndPort> externalAddresses;
    public final String motd;
    public final Optional<Semver> version;
    public final long affinityMillis;
    public final boolean isRetired;
    public final double tickMillis;
    public final int onlinePlayers;
    public final int maximumPlayers;

    private GongdaobeiServiceParams(Map<String, String> serviceParams) {
        this.isFallback = Boolean.parseBoolean(serviceParams.getOrDefault("config:fallback", "false"));
        this.externalAddresses = List.of(Arrays
                .stream(StringUtils.split(serviceParams.getOrDefault("config:external", "")))
                .flatMap(s -> GongdaobeiUtil.getHostAndPort(s, "").stream()).toArray(HostAndPort[]::new));
        this.motd = serviceParams.getOrDefault("config:motd", "");
        this.version = Optional.ofNullable(serviceParams.get("config:version")).flatMap(GongdaobeiUtil::getVersion);
        this.affinityMillis = Long.parseUnsignedLong(serviceParams.getOrDefault("config:affinity", "0"));
        this.isRetired = !Boolean.parseBoolean(serviceParams.getOrDefault("status:register", "false"));
        this.tickMillis = Math.max(0.0, Double.parseDouble(serviceParams.getOrDefault("status:tick", "0")));
        this.onlinePlayers = Integer.parseUnsignedInt(serviceParams.getOrDefault("status:online", "0"));
        this.maximumPlayers = Integer.parseUnsignedInt(serviceParams.getOrDefault("status:maximum", "0"));
    }

    public GongdaobeiServiceParams(GongdaobeiTomlConfig.Service config,
                                   boolean isServerRetired, String motd, int serverPort,
                                   double tickMillis, int onlinePlayers, int maximumPlayers) {
        this.isFallback = config.isFallbackServer();
        this.externalAddresses = List.of(config.externalAddresses()
                .stream().map(h -> h.withDefaultPort(serverPort)).toArray(HostAndPort[]::new));
        this.motd = motd;
        this.version = config.version().toSemver();
        this.affinityMillis = config.affinityMillis();
        this.isRetired = isServerRetired;
        this.tickMillis = tickMillis;
        this.onlinePlayers = onlinePlayers;
        this.maximumPlayers = maximumPlayers;
    }

    public static GongdaobeiServiceParams fromParams(Map<String, String> serviceParams) {
        return new GongdaobeiServiceParams(serviceParams);
    }

    public Map<String, String> toParams() {
        return Map.of(
                "config:fallback", Boolean.toString(this.isFallback),
                "config:external", this.externalAddresses.stream()
                        .map(HostAndPort::toString).collect(Collectors.joining("\t")),
                "config:motd", this.motd,
                "config:version", this.version.map(Semver::toString).orElse(""),
                "config:affinity", Long.toUnsignedString(this.affinityMillis),
                "status:register", Boolean.toString(!this.isRetired),
                "status:tick", String.format("%.8f", this.tickMillis),
                "status:online", Integer.toUnsignedString(this.onlinePlayers),
                "status:maximum", Integer.toUnsignedString(this.maximumPlayers));
    }
}
