package com.borderra.synapse;

import com.borderra.synapse.api.SynapseApi;
import com.borderra.synapse.api.SynapseApiImpl;
import com.borderra.synapse.command.DiscordCommand;
import com.borderra.synapse.config.SynapseConfig;
import com.borderra.synapse.config.SynapseSettings;
import com.borderra.synapse.database.DatabaseManager;
import com.borderra.synapse.database.SchemaInitializer;
import com.borderra.synapse.database.TableNames;
import com.borderra.synapse.discord.DiscordBotService;
import com.borderra.synapse.linking.LinkRepository;
import com.borderra.synapse.linking.LinkService;
import com.borderra.synapse.notification.NotificationProcessor;
import com.borderra.synapse.platform.PlatformScheduler;
import com.borderra.synapse.platform.SynapseTranslations;
import com.borderra.borderralib.translation.TranslationService;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;

import java.sql.SQLException;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

public final class SynapsePlugin extends JavaPlugin {
    private SynapseSettings settings;
    private PlatformScheduler scheduler;
    private ExecutorService databaseExecutor;
    private DatabaseManager databaseManager;
    private LinkRepository linkRepository;
    private LinkService linkService;
    private DiscordBotService discordBotService;
    private NotificationProcessor notificationProcessor;
    private TranslationService translationService;
    private SynapseTranslations translations;
    private ScheduledTask cleanupTask;

    @Override
    public void onEnable() {
        try {
            this.settings = SynapseConfig.load(this);
            this.scheduler = new PlatformScheduler(this);
            this.databaseExecutor = Executors.newFixedThreadPool(
                    Math.max(2, settings.database().poolSize()),
                    new SynapseThreadFactory()
            );

            TableNames tableNames = TableNames.fromPrefix(settings.database().tablePrefix());
            this.databaseManager = new DatabaseManager(settings.database());
            SchemaInitializer.initialize(databaseManager.dataSource(), tableNames);

            this.linkRepository = new LinkRepository(databaseManager.dataSource(), tableNames, databaseExecutor);
            this.linkService = new LinkService(linkRepository, settings.linking());
            this.translationService = new TranslationService(this, SynapsePlugin.class);
            this.translationService.load();
            this.translations = new SynapseTranslations(translationService);

            this.discordBotService = new DiscordBotService(
                    settings.discord(),
                    linkService,
                    translations,
                    scheduler,
                    getLogger()
            );
            this.discordBotService.start();

            registerCommand();
            registerApi();
            startNotificationProcessor();
            startExpiredCodeCleanup();

            getLogger().info("Synapse enabled.");
        } catch (SQLException | RuntimeException e) {
            getLogger().severe("Synapse failed to enable: " + e.getMessage());
            getServer().getPluginManager().disablePlugin(this);
        }
    }

    @Override
    public void onDisable() {
        getServer().getServicesManager().unregisterAll(this);
        if (cleanupTask != null) {
            cleanupTask.cancel();
            cleanupTask = null;
        }
        if (notificationProcessor != null) {
            notificationProcessor.close();
            notificationProcessor = null;
        }
        if (discordBotService != null) {
            discordBotService.close();
            discordBotService = null;
        }
        if (databaseManager != null) {
            databaseManager.close();
            databaseManager = null;
        }
        if (databaseExecutor != null) {
            databaseExecutor.shutdown();
            try {
                if (!databaseExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                    databaseExecutor.shutdownNow();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                databaseExecutor.shutdownNow();
            }
            databaseExecutor = null;
        }
        getLogger().info("Synapse disabled.");
    }

    private void registerCommand() {
        PluginCommand command = getCommand("discord");
        if (command == null) {
            throw new IllegalStateException("Command 'discord' is missing from plugin.yml.");
        }
        DiscordCommand executor = new DiscordCommand(
                linkService,
                settings.linking(),
                translations,
                scheduler,
                getLogger()
        );
        command.setExecutor(executor);
        command.setTabCompleter(executor);
    }

    private void registerApi() {
        SynapseApi api = new SynapseApiImpl(linkService, discordBotService);
        getServer().getServicesManager().register(SynapseApi.class, api, this, ServicePriority.Normal);
    }

    private void startNotificationProcessor() {
        this.notificationProcessor = new NotificationProcessor(
                linkRepository,
                discordBotService,
                settings.notifications(),
                translations,
                scheduler,
                getLogger()
        );
        notificationProcessor.start();
    }

    private void startExpiredCodeCleanup() {
        Duration interval = settings.linking().expiredCodeCleanupInterval();
        cleanupTask = scheduler.runAsyncRepeating(
                () -> linkService.deleteExpiredCodes().exceptionally(throwable -> {
                    getLogger().warning("Failed to clean up expired link codes: " + throwable.getMessage());
                    return 0;
                }),
                interval,
                interval
        );
    }

    private static final class SynapseThreadFactory implements ThreadFactory {
        private int count;

        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "synapse-db-" + ++count);
            thread.setDaemon(true);
            return thread;
        }
    }
}
