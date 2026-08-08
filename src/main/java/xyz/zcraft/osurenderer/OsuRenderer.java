package xyz.zcraft.osurenderer;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import xyz.zcraft.osurenderer.config.AppConfig;
import xyz.zcraft.osurenderer.config.ConfigLoader;
import xyz.zcraft.osurenderer.runtime.RendererApplication;

import java.io.IOException;

public final class OsuRenderer {
    private static final Logger LOG = LogManager.getLogger(OsuRenderer.class);

    static void main() {
        if (!ConfigLoader.configExists()) {
            try {
                ConfigLoader.copyDefaultConfig();
                LOG.warn("Created config.yml. Configure danserPath and restart osuRenderer.");
            } catch (IOException e) {
                LOG.error("Failed to create config.yml", e);
            }
            return;
        }

        try {
            AppConfig config = ConfigLoader.loadConfig();
            try (RendererApplication application = new RendererApplication(config)) {
                Thread shutdownHook = new Thread(application::close, "osurenderer-shutdown");
                Runtime.getRuntime().addShutdownHook(shutdownHook);
                try {
                    application.run();
                } finally {
                    try {
                        Runtime.getRuntime().removeShutdownHook(shutdownHook);
                    } catch (IllegalStateException ignored) {
                    }
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOG.info("osuRenderer shutdown interrupted");
        } catch (RuntimeException | IOException e) {
            LOG.error("Failed to start osuRenderer", e);
            System.exit(1);
        }
    }
}
