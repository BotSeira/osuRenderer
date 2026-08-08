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
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

public final class WebServer implements Closeable {
    private static final Logger LOG = LogManager.getLogger(WebServer.class);
    private final AppConfig config;
    private final ReplayRenderService renderService;
    private final Javalin app;
    private final AtomicBoolean running = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicLong requests = new AtomicLong();
    private final AtomicLong failures = new AtomicLong();

    public WebServer(AppConfig config) throws IOException {
        this.config = config;
        this.renderService = new ReplayRenderService(config.renderer());
        RenderController controller = new RenderController(renderService);
        this.app = Javalin.create(javalin -> {
            javalin.http.maxRequestSize = config.webserver().maxRequestSizeMb() * 1024L * 1024L;
            javalin.routes.before(context -> {
                requests.incrementAndGet();
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
                    .post("/cache/control", controller::cacheControl)
                    .post("/cache/fetch", controller::cacheFetch)
                    .get("/renders/status", controller::overview)
                    .post("/renders", controller::create)
                    .get("/renders/{jobId}/status", controller::status)
                    .get("/renders/{jobId}/video", controller::video)
                    .delete("/renders/{jobId}", controller::delete)
                    .exception(QueueFullException.class, (error, context) ->
                            fail(context, 429, error.getMessage()))
                    .exception(MissingCacheAssetException.class, (error, context) ->
                            fail(context, 409, error.getMessage()))
                    .exception(IllegalArgumentException.class, (error, context) ->
                            fail(context, 400, error.getMessage()))
                    .exception(Exception.class, (error, context) -> {
                        failures.incrementAndGet();
                        LOG.error("Request failed: {} {}", context.method(), context.path(), error);
                        context.status(500).result("Internal renderer error");
                    });
        });
    }

    public void start() {
        app.start(config.webserver().port());
        running.set(true);
        LOG.info("osuRenderer listening on port {}", config.webserver().port());
    }

    private void fail(io.javalin.http.Context context, int status, String message) {
        failures.incrementAndGet();
        context.status(status).result(message);
    }

    public ServerStatus status() {
        return new ServerStatus(running.get() && !closed.get(), requests.get(), failures.get(), renderService.status());
    }

    public List<xyz.zcraft.osurenderer.model.JobProgress> listJobs() {
        return renderService.listJobs();
    }

    public xyz.zcraft.osurenderer.model.JobProgress getJob(String jobId) {
        return renderService.getProgress(jobId);
    }

    public void deleteJob(String jobId) throws IOException {
        renderService.deleteJob(jobId);
    }

    public xyz.zcraft.osurenderer.service.RenderAssetCache.CacheSummary cacheSummary() {
        return renderService.cacheSummary();
    }

    public boolean hasCachedAsset(xyz.zcraft.osurenderer.service.RenderAssetCache.CacheType type, long id) {
        return renderService.hasCachedAsset(type, id);
    }

    public boolean removeCachedAsset(xyz.zcraft.osurenderer.service.RenderAssetCache.CacheType type, long id)
            throws IOException {
        return renderService.removeCachedAsset(type, id);
    }

    public int clearCache(xyz.zcraft.osurenderer.service.RenderAssetCache.CacheSelection selection)
            throws IOException {
        return renderService.clearCache(selection);
    }

    public xyz.zcraft.osurenderer.model.CacheControlResult controlCache(
            xyz.zcraft.osurenderer.model.CacheControlRequest request) {
        return renderService.controlCache(request);
    }

    public int cleanupNow() {
        return renderService.cleanupNow();
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
        if (closed.compareAndSet(false, true)) {
            running.set(false);
            app.stop();
            renderService.close();
        }
    }

    public record ServerStatus(
            boolean running,
            long requests,
            long failures,
            ReplayRenderService.ServiceStatus renderer
    ) {
    }
}
