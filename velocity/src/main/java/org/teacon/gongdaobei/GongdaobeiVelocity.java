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

import com.google.inject.Inject;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Logger;

@Plugin(id = "gongdaobei",
        name = GongdaobeiVelocityParameters.NAME,
        version = GongdaobeiVelocityParameters.VERSION,
        authors = GongdaobeiVelocityParameters.AUTHORS,
        description = GongdaobeiVelocityParameters.DESCRIPTION)
public final class GongdaobeiVelocity {
    private GongdaobeiTomlConfig.Velocity config;
    private GongdaobeiVelocityBandwidthCounter collector;
    private GongdaobeiVelocityNetworkEventHandler handler;

    public final Path dataDir;
    public final Logger logger;
    public final ProxyServer server;

    @Inject
    public GongdaobeiVelocity(ProxyServer server, Logger logger, @DataDirectory Path dataDir) {
        this.dataDir = dataDir;
        this.logger = logger;
        this.server = server;
    }

    @Subscribe
    public void onEnable(ProxyInitializeEvent event) {
        try {
            var dataDir = Files.exists(this.dataDir) ? this.dataDir : Files.createDirectories(this.dataDir);
            this.logger.info("Loading from the configuration file ...");
            var file = dataDir.resolve("gongdaobei.toml");
            this.config = GongdaobeiTomlConfig.Velocity.load(file).save(file);
            var redisUri = this.config.discoveryRedisUri();
            var desensitizeRedisUri = GongdaobeiUtil.desensitizeRedisUri(redisUri.getValue());
            this.logger.info("- Discovery Redis URI: " + desensitizeRedisUri + " (resolved from " + redisUri + ")");
            this.collector = new GongdaobeiVelocityBandwidthCounter(this, this.config.prometheusServerPort() > 0);
            this.handler = new GongdaobeiVelocityNetworkEventHandler(this, this.config, this.collector);
        } catch (IOException e) {
            throw new IllegalArgumentException(e);
        }
    }

    @Subscribe
    public void onDisable(ProxyShutdownEvent event) {
        try {
            var dataDir = Files.exists(this.dataDir) ? this.dataDir : Files.createDirectories(this.dataDir);
            this.logger.info("Saving to the configuration file ...");
            var file = dataDir.resolve("gongdaobei.toml");
            this.config.save(file);
            this.collector.close();
            this.handler.close();
        } catch (IOException e) {
            throw new IllegalArgumentException(e);
        }
    }
}
