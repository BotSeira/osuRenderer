package xyz.zcraft.osurenderer.config;

public record WebserverConfig(int port, long maxRequestSizeMb) {
    public WebserverConfig {
        port = port > 0 ? port : 8722;
        maxRequestSizeMb = maxRequestSizeMb > 0 ? maxRequestSizeMb : 512;
    }
}
