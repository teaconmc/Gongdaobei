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

import io.prometheus.metrics.core.metrics.Counter;
import io.prometheus.metrics.core.metrics.Gauge;

public final class GongdaobeiVelocityPromMetrics {
    public static final Counter totalPings;
    public static final Counter totalLogins;
    public static final Counter totalLoginsWithAffinity;
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
        totalPings = Counter.builder()
                .name("gongdaobei_pings_total")
                .help("Total ping requests by clients").register();
        totalLogins = Counter.builder()
                .name("gongdaobei_logins_total")
                .help("Total login requests by clients").register();
        totalLoginsWithAffinity = Counter.builder()
                .name("gongdaobei_logins_with_affinity_total")
                .help("Total login requests by clients with affinity").register();
        onlinePlayers = Gauge.builder()
                .name("gongdaobei_online_players")
                .help("Online players of all the servers").register();
        fallbackOnlinePlayers = Gauge.builder()
                .name("gongdaobei_fallback_online_players")
                .help("Online players of fallback servers").register();
        targetedOnlinePlayers = Gauge.builder()
                .name("gongdaobei_targeted_online_players")
                .help("Online players of servers with the same external address").labelNames("address").register();
        maximumPlayers = Gauge.builder()
                .name("gongdaobei_maximum_players")
                .help("Maximum players of all the servers").register();
        fallbackMaximumPlayers = Gauge.builder()
                .name("gongdaobei_fallback_maximum_players")
                .help("Maximum players of servers marked as fallback servers").register();
        targetedMaximumPlayers = Gauge.builder()
                .name("gongdaobei_targeted_maximum_players")
                .help("Maximum players of servers with the same external address").labelNames("address").register();
        serviceInstances = Gauge.builder()
                .name("gongdaobei_service_instances")
                .help("The instance count of servers").register();
        fallbackServiceInstances = Gauge.builder()
                .name("gongdaobei_fallback_service_instances")
                .help("The instance count of fallback servers").register();
        targetedServiceInstances = Gauge.builder()
                .name("gongdaobei_targeted_service_instances")
                .help("The instance count of servers with the same external address").labelNames("address").register();
        latestFallbackServiceInstances = Gauge.builder()
                .name("gongdaobei_latest_fallback_service_instances")
                .help("The instance count of fallback servers whose version is latest").register();
        latestTargetedServiceInstances = Gauge.builder()
                .name("gongdaobei_latest_targeted_service_instances")
                .help("The instance count of servers with the same external address whose version is latest").labelNames("address").register();
        servicePerTick = Gauge.builder()
                .name("gongdaobei_service_tick_duration_seconds")
                .help("The time spent per tick in seconds").labelNames("name").register();
        fallbackServicePerTick = Gauge.builder()
                .name("gongdaobei_fallback_service_tick_duration_seconds")
                .help("The time spent per tick in seconds of fallback servers").labelNames("name").register();
        targetedServicePerTick = Gauge.builder()
                .name("gongdaobei_targeted_service_tick_duration_seconds")
                .help("The time spent per tick in seconds of servers grouped by external addresses").labelNames("address", "name").register();
    }
}
