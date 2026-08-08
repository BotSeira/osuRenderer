package xyz.zcraft.osurenderer.console;

import xyz.zcraft.osurenderer.model.JobProgress;
import xyz.zcraft.osurenderer.model.CacheControlRequest;
import xyz.zcraft.osurenderer.model.CacheControlResult;
import xyz.zcraft.osurenderer.network.WebServer;
import xyz.zcraft.osurenderer.service.RenderAssetCache;

import java.io.IOException;
import java.util.List;

public interface RendererConsoleAccess {
    WebServer.ServerStatus status();

    List<JobProgress> listJobs();

    JobProgress getJob(String jobId);

    void deleteJob(String jobId) throws IOException;

    RenderAssetCache.CacheSummary cacheSummary();

    boolean hasCachedAsset(RenderAssetCache.CacheType type, long id);

    boolean removeCachedAsset(RenderAssetCache.CacheType type, long id) throws IOException;

    int clearCache(RenderAssetCache.CacheSelection selection) throws IOException;

    CacheControlResult controlCache(CacheControlRequest request);

    int cleanupNow();

    void requestStop();
}
