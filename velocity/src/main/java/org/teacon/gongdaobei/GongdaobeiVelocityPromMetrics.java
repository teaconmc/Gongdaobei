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

import io.prometheus.client.Counter;
import io.prometheus.client.Gauge;

public final class GongdaobeiVelocityPromMetrics {
    public static final Counter totalPings;
    public static final Counter totalLogins;
    public static final Counter totalLoginsWithAffinity;
    public static final Counter totalPlayerNetworkBytes;
    public static final Counter totalServiceNetworkBytes;
    public static final Counter totalFallbackServiceNetworkBytes;
    public static final Counter totalTargetedServiceNetworkBytes;
    public static final Counter totalPlayerUncompressedNetworkBytes;
    public static final Counter totalServiceUncompressedNetworkBytes;
    public static final Counter totalFallbackServiceUncompressedNetworkBytes;
    public static final Counter totalTargetedServiceUncompressedNetworkBytes;
    public static final Gauge onlinePlayers;
    public static final Gauge fallbackOnlinePlayers;
    public static final Gauge targetedOnlinePlayers;
    public static final Gauge maximumPlayers;
    public static final Gauge fallbackMaximumPlayers;
    public static final Gauge targetedMaximumPlayers;
    public static final Gauge serviceInstances;
    public static final Gauge fallbackServiceInstances;
    public static final Gauge targetedServiceInstances;
    public static final Gauge latestFallbackServiceInstances;
    public static final Gauge latestTargetedServiceInstances;
    public static final Gauge servicePerTick;
    public static final Gauge fallbackServicePerTick;
    public static final Gauge targetedServicePerTick;

    static {
        totalPings = Counter.build(
                "gongdaobei_pings_total",
                "Total ping requests by clients").register();
        totalLogins = Counter.build(
                "gongdaobei_logins_total",
                "Total login requests by clients").register();
        totalLoginsWithAffinity = Counter.build(
                "gongdaobei_logins_with_affinity_total",
                "Total login requests by clients with affinity").register();
        totalPlayerNetworkBytes = Counter.build(
                "gongdaobei_player_network_bytes_total",
                "Total network bytes received from or transmitted to " +
                        "players (including proxy pings)").labelNames("channel").register();
        totalServiceNetworkBytes = Counter.build(
                "gongdaobei_service_network_bytes_total",
                "Total network bytes received from or transmitted to " +
                        "services").labelNames("channel", "name").register();
        totalFallbackServiceNetworkBytes = Counter.build(
                "gongdaobei_fallback_service_network_bytes_total",
                "Total network bytes received from or transmitted to " +
                        "fallback servers").labelNames("channel", "name").register();
        totalTargetedServiceNetworkBytes = Counter.build(
                "gongdaobei_targeted_service_network_bytes_total",
                "Total network bytes received from or transmitted to " +
                        "servers grouped by external addresses").labelNames("channel", "address", "name").register();
        totalPlayerUncompressedNetworkBytes = Counter.build(
                "gongdaobei_player_uncompressed_network_bytes_total",
                "Total network bytes after decompression received from or transmitted to " +
                        "players (including proxy pings)").labelNames("channel").register();
        totalServiceUncompressedNetworkBytes = Counter.build(
                "gongdaobei_service_uncompressed_network_bytes_total",
                "Total network bytes after decompression received from or transmitted to " +
                        "services").labelNames("channel", "name").register();
        totalFallbackServiceUncompressedNetworkBytes = Counter.build(
                "gongdaobei_fallback_service_uncompressed_network_bytes_total",
                "Total network bytes after decompression received from or transmitted to " +
                        "fallback servers").labelNames("channel", "name").register();
        totalTargetedServiceUncompressedNetworkBytes = Counter.build(
                "gongdaobei_targeted_service_uncompressed_network_bytes_total",
                "Total network bytes after decompression received from or transmitted to " +
                        "servers grouped by external addresses").labelNames("channel", "address", "name").register();
        onlinePlayers = Gauge.build(
                "gongdaobei_online_players",
                "Online players of all the servers").register();
        fallbackOnlinePlayers = Gauge.build(
                "gongdaobei_fallback_online_players",
                "Online players of fallback servers").register();
        targetedOnlinePlayers = Gauge.build(
                "gongdaobei_targeted_online_players",
                "Online players of servers with the same external address").labelNames("address").register();
        maximumPlayers = Gauge.build(
                "gongdaobei_maximum_players",
                "Maximum players of all the servers").register();
        fallbackMaximumPlayers = Gauge.build(
                "gongdaobei_fallback_maximum_players",
                "Maximum players of servers marked as fallback servers").register();
        targetedMaximumPlayers = Gauge.build(
                "gongdaobei_targeted_maximum_players",
                "Maximum players of servers with the same external address").labelNames("address").register();
        serviceInstances = Gauge.build(
                "gongdaobei_service_instances",
                "The instance count of servers").register();
        fallbackServiceInstances = Gauge.build(
                "gongdaobei_fallback_service_instances",
                "The instance count of fallback servers").register();
        targetedServiceInstances = Gauge.build(
                "gongdaobei_targeted_service_instances",
                "The instance count of servers with the same external address").labelNames("address").register();
        latestFallbackServiceInstances = Gauge.build(
                "gongdaobei_latest_fallback_service_instances",
                "The instance count of fallback servers whose version is latest").register();
        latestTargetedServiceInstances = Gauge.build(
                "gongdaobei_latest_targeted_service_instances",
                "The instance count of servers with the same external address whose version is latest").labelNames("address").register();
        servicePerTick = Gauge.build(
                "gongdaobei_service_tick_duration_seconds",
                "The time spent per tick in seconds").labelNames("name").register();
        fallbackServicePerTick = Gauge.build(
                "gongdaobei_fallback_service_tick_duration_seconds",
                "The time spent per tick in seconds of fallback servers").labelNames("name").register();
        targetedServicePerTick = Gauge.build(
                "gongdaobei_targeted_service_tick_duration_seconds",
                "The time spent per tick in seconds of servers grouped by external addresses").labelNames("address", "name").register();
    }
}
