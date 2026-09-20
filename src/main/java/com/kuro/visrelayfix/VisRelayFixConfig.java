package com.kuro.visrelayfix;

import net.minecraftforge.common.config.Configuration;

import java.io.File;

final class VisRelayFixConfig {
    private static volatile boolean loggingEnabled = true;

    private VisRelayFixConfig() {
    }

    static void initialize(File gameDirectory) {
        if (gameDirectory == null) {
            return;
        }

        try {
            File configFile = new File(new File(gameDirectory, "config"), "ThaumcraftVisRelayFix.cfg");
            Configuration config = new Configuration(configFile);
            config.load();
            loggingEnabled = config.getBoolean(
                    "enableLogging",
                    Configuration.CATEGORY_GENERAL,
                    true,
                    "Write compact relay rebuild summaries to the server log."
            );
            if (config.hasChanged()) {
                config.save();
            }
        } catch (Throwable ignored) {
            // Logging remains enabled if Forge configuration is unavailable early in startup.
            loggingEnabled = true;
        }
    }

    static boolean isLoggingEnabled() {
        return loggingEnabled;
    }
}
