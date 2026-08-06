package xyz.zcraft.osurenderer.config;

public record RendererConfig(
        String apiKey,
        String danserPath,
        String workPath,
        int renderQueueSize,
        int renderThreads,
        int resultTtlMinutes,
        int renderTimeoutMinutes
) {
    public RendererConfig {
        apiKey = apiKey == null ? "" : apiKey;
        danserPath = danserPath == null ? "danser/danser-cli" : danserPath;
        workPath = workPath == null || workPath.isBlank() ? "data" : workPath;
        renderQueueSize = renderQueueSize > 0 ? renderQueueSize : 5;
        renderThreads = renderThreads > 0 ? renderThreads : 1;
        resultTtlMinutes = resultTtlMinutes > 0 ? resultTtlMinutes : 15;
        renderTimeoutMinutes = renderTimeoutMinutes > 0 ? renderTimeoutMinutes : 10;
    }
}
