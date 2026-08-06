package xyz.zcraft.osurenderer.config;

public record RendererConfig(
        String apiKey,
        String danserPath,
        String workPath,
        String cachePath,
        int renderQueueSize,
        int renderThreads,
        int resultTtlMinutes,
        int renderTimeoutMinutes,
        String danserConfigPath
) {
    public RendererConfig {
        apiKey = apiKey == null ? "" : apiKey;
        danserPath = danserPath == null ? "danser/danser-cli" : danserPath;
        workPath = workPath == null || workPath.isBlank() ? "data" : workPath;
        cachePath = cachePath == null || cachePath.isBlank()
                ? java.nio.file.Path.of(workPath).resolve("cache").toString()
                : cachePath;
        renderQueueSize = renderQueueSize > 0 ? renderQueueSize : 5;
        renderThreads = renderThreads > 0 ? renderThreads : 1;
        resultTtlMinutes = resultTtlMinutes > 0 ? resultTtlMinutes : 15;
        renderTimeoutMinutes = renderTimeoutMinutes > 0 ? renderTimeoutMinutes : 10;
        danserConfigPath = null;
    }
}
