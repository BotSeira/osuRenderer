package xyz.zcraft.osurenderer.network;

import com.google.gson.JsonObject;
import io.javalin.Javalin;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import xyz.zcraft.osurenderer.config.AppConfig;
import xyz.zcraft.osurenderer.service.MissingCacheAssetException;
import xyz.zcraft.osurenderer.service.QueueFullException;
import xyz.zcraft.osurenderer.service.ReplayRenderService;

import java.io.Closeable;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

public final class WebServer implements Closeable {
    private static final Logger LOG = LogManager.getLogger(WebServer.class);
    private final AppConfig config;
    private final ReplayRenderService renderService;
    private final Javalin app;

    public WebServer(AppConfig config) throws IOException {
        this.config = config;
        this.renderService = new ReplayRenderService(config.renderer());
        RenderController controller = new RenderController(renderService);
        this.app = Javalin.create(javalin -> {
            javalin.http.maxRequestSize = config.webserver().maxRequestSizeMb() * 1024L * 1024L;
            javalin.routes.before(context -> {
                if (!context.path().equals("/health")) {
                    authorize(context);
                }
            });
            javalin.routes
                    .get("/health", context -> {
                        JsonObject response = new JsonObject();
                        response.addProperty("ok", true);
                        context.contentType("application/json").result(response.toString());
                    })
                    .post("/cache/status", controller::cacheStatus)
                    .get("/renders/status", controller::overview)
                    .post("/renders", controller::create)
                    .get("/renders/{jobId}/status", controller::status)
                    .get("/renders/{jobId}/video", controller::video)
                    .delete("/renders/{jobId}", controller::delete)
                    .exception(QueueFullException.class, (error, context) ->
                            context.status(429).result(error.getMessage()))
                    .exception(MissingCacheAssetException.class, (error, context) ->
                            context.status(409).result(error.getMessage()))
                    .exception(IllegalArgumentException.class, (error, context) ->
                            context.status(400).result(error.getMessage()))
                    .exception(Exception.class, (error, context) -> {
                        LOG.error("Request failed: {} {}", context.method(), context.path(), error);
                        context.status(500).result("Internal renderer error");
                    });
        });
    }

    public void start() {
        app.start(config.webserver().port());
        LOG.info("osuRenderer listening on port {}", config.webserver().port());
    }

    private void authorize(io.javalin.http.Context context) {
        String expected = config.renderer().apiKey();
        if (expected.isBlank()) {
            return;
        }
        String actual = context.header("Authorization");
        String expectedHeader = "Bearer " + expected;
        boolean matches = actual != null && MessageDigest.isEqual(
                expectedHeader.getBytes(StandardCharsets.UTF_8),
                actual.getBytes(StandardCharsets.UTF_8));
        if (!matches) {
            context.status(401).result("Unauthorized");
            context.skipRemainingHandlers();
        }
    }

    @Override
    public void close() {
        app.stop();
        renderService.close();
    }
}
