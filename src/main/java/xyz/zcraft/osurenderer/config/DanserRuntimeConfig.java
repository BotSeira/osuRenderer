package xyz.zcraft.osurenderer.config;

import java.util.Map;

public record DanserRuntimeConfig(
        Map<String, String> envVars,
        String commandPrefix,
        String commandPrefixWin
) {
}
