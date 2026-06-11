package com.borderra.synapse.platform;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

public final class PlatformScheduler {
    private final Plugin plugin;

    public PlatformScheduler(Plugin plugin) {
        this.plugin = plugin;
    }

    public void runGlobal(Runnable runnable) {
        plugin.getServer().getGlobalRegionScheduler().execute(plugin, runnable);
    }

    public void runForPlayer(Player player, Runnable runnable) {
        player.getScheduler().execute(plugin, runnable, null, 1L);
    }

    public ScheduledTask runAsyncRepeating(Runnable runnable, Duration delay, Duration period) {
        return plugin.getServer().getAsyncScheduler().runAtFixedRate(
                plugin,
                task -> runnable.run(),
                Math.max(1L, delay.toMillis()),
                Math.max(1L, period.toMillis()),
                TimeUnit.MILLISECONDS
        );
    }

    public ScheduledTask runAsyncLater(Runnable runnable, Duration delay) {
        return plugin.getServer().getAsyncScheduler().runDelayed(
                plugin,
                task -> runnable.run(),
                Math.max(1L, delay.toMillis()),
                TimeUnit.MILLISECONDS
        );
    }

    public void cancel(ScheduledTask task) {
        if (task != null) {
            task.cancel();
        }
    }
}
