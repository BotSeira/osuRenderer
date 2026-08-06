package xyz.zcraft.osurenderer.config;

public record AppConfig(RendererConfig renderer, WebserverConfig webserver) {
    public AppConfig {
        renderer = renderer == null ? new RendererConfig(null, null, null, null, 0, 0, 0, 0) : renderer;
        webserver = webserver == null ? new WebserverConfig(0, 0) : webserver;
    }
}
