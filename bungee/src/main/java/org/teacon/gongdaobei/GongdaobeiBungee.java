package org.teacon.gongdaobei;

import net.md_5.bungee.api.event.ServerConnectEvent;
import net.md_5.bungee.api.plugin.Listener;
import net.md_5.bungee.api.plugin.Plugin;
import net.md_5.bungee.event.EventHandler;

public final class GongdaobeiBungee extends Plugin {
    @Override
    public void onEnable() {
        this.getLogger().info("HELLO FROM GONGDAOBEI BUNGEE SETUP");
        this.getProxy().getPluginManager().registerListener(this, new EventListener());
    }

    public final class EventListener implements Listener {
        @EventHandler
        public void on(ServerConnectEvent event) {
            if (event.getPlayer() != null) {
                var player = event.getPlayer().getSocketAddress();
                var target = event.getTarget().getSocketAddress();
                GongdaobeiBungee.this.getLogger().info("Connection from " + player + " to " + target);
            }
        }
    }
}
