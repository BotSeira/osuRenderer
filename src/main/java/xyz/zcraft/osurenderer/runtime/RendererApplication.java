package xyz.zcraft.osurenderer.runtime;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import xyz.zcraft.osurenderer.config.AppConfig;
import xyz.zcraft.osurenderer.console.JLineConsole;
import xyz.zcraft.osurenderer.console.RendererConsoleAccess;
import xyz.zcraft.osurenderer.console.RendererConsoleProcessor;
import xyz.zcraft.osurenderer.model.JobProgress;
import xyz.zcraft.osurenderer.model.CacheControlRequest;
import xyz.zcraft.osurenderer.model.CacheControlResult;
import xyz.zcraft.osurenderer.network.WebServer;
import xyz.zcraft.osurenderer.service.RenderAssetCache;

import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

public final class RendererApplication implements AutoCloseable, RendererConsoleAccess {
    private static final Logger LOG = LogManager.getLogger(RendererApplication.class);

    private final WebServer server;
    private final JLineConsole console;
    private final CountDownLatch stopSignal = new CountDownLatch(1);
    private final AtomicBoolean closed = new AtomicBoolean();

    public RendererApplication(AppConfig config) throws IOException {
        Objects.requireNonNull(config, "config");
        this.server = new WebServer(config);
        this.console = new JLineConsole(new RendererConsoleProcessor(config, this));
    }

    public void run() throws InterruptedException {
        server.start();
        console.start();
        LOG.info("osuRenderer is ready");
        stopSignal.await();
    }

    @Override
    public WebServer.ServerStatus status() {
        return server.status();
    }

    @Override
    public List<JobProgress> listJobs() {
        return server.listJobs();
    }

    @Override
    public JobProgress getJob(String jobId) {
        return server.getJob(jobId);
    }

    @Override
    public void deleteJob(String jobId) throws IOException {
        server.deleteJob(jobId);
    }

    @Override
    public RenderAssetCache.CacheSummary cacheSummary() {
        return server.cacheSummary();
    }

    @Override
    public boolean hasCachedAsset(RenderAssetCache.CacheType type, long id) {
        return server.hasCachedAsset(type, id);
    }

    @Override
    public boolean removeCachedAsset(RenderAssetCache.CacheType type, long id) throws IOException {
        return server.removeCachedAsset(type, id);
    }

    @Override
    public int clearCache(RenderAssetCache.CacheSelection selection) throws IOException {
        return server.clearCache(selection);
    }

    @Override
    public CacheControlResult controlCache(CacheControlRequest request) {
        return server.controlCache(request);
    }

    @Override
    public int cleanupNow() {
        return server.cleanupNow();
    }

    @Override
    public void requestStop() {
        stopSignal.countDown();
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            stopSignal.countDown();
            console.close();
            server.close();
        }
    }
}
