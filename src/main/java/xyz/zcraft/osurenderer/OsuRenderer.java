package xyz.zcraft.osurenderer;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import xyz.zcraft.osurenderer.config.AppConfig;
import xyz.zcraft.osurenderer.config.ConfigLoader;
import xyz.zcraft.osurenderer.network.WebServer;

import java.io.IOException;

public final class OsuRenderer {
    private static final Logger LOG = LogManager.getLogger(OsuRenderer.class);

    private OsuRenderer() {
    }

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
            WebServer server = new WebServer(config);
            server.start();
            Runtime.getRuntime().addShutdownHook(new Thread(server::close));
        } catch (RuntimeException | IOException e) {
            LOG.error("Failed to start osuRenderer", e);
            System.exit(1);
        }
    }
}
