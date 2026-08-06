package xyz.zcraft.osurenderer.config;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.dataformat.yaml.YAMLFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

public final class ConfigLoader {
    public static final Path CONFIG_PATH = Path.of("config.yml");

    private ConfigLoader() {
    }

    public static AppConfig loadConfig() {
        return new ObjectMapper(new YAMLFactory()).readValue(CONFIG_PATH, AppConfig.class);
    }

    public static boolean configExists() {
        return Files.exists(CONFIG_PATH);
    }

    public static void copyDefaultConfig() throws IOException {
        try (var input = ConfigLoader.class.getResourceAsStream("/osurenderer-example-config.yml")) {
            if (input == null) {
                throw new IOException("Default config not found");
            }
            Files.copy(input, CONFIG_PATH, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
