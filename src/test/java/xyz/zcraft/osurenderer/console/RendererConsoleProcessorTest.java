package xyz.zcraft.osurenderer.console;

import org.junit.jupiter.api.Test;
import xyz.zcraft.osurenderer.config.*;
import xyz.zcraft.osurenderer.model.JobProgress;
import xyz.zcraft.osurenderer.model.JobStatus;
import xyz.zcraft.osurenderer.model.CacheControlRequest;
import xyz.zcraft.osurenderer.model.CacheControlResult;
import xyz.zcraft.osurenderer.network.WebServer;
import xyz.zcraft.osurenderer.service.RenderAssetCache;
import xyz.zcraft.osurenderer.service.ReplayRenderService;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class RendererConsoleProcessorTest {
    private static final String JOB_ID = "00000000-0000-0000-0000-000000000001";

    @Test
    void helpCoversCommandsAndCommonOutputIsEnglish() {
        Fixture fixture = fixture();
        for (String command : List.of("help", "status", "queue", "jobs", "cache status",
                "config show", "log show", "system", "stop", "unknown")) {
            String output = fixture.processor.execute(command).message();
            assertFalse(output.matches("(?s).*\\p{IsHan}.*"), command + ": " + output);
        }
        String help = fixture.processor.execute("help").message();
        RendererConsoleProcessor.rootCommands().forEach(command -> assertTrue(help.contains(command), command));
    }

    @Test
    void apiKeyIsRedactedAndDestructiveCommandsRequireConfirmation() {
        Fixture fixture = fixture();
        String output = fixture.processor.execute("config show").message();
        assertFalse(output.contains("renderer-api-secret"));
        assertTrue(output.contains("redacted"));
        assertFalse(fixture.processor.execute("cache clear all").success());
        assertTrue(fixture.processor.execute("cache clear all confirm").success());
        assertEquals(RenderAssetCache.CacheSelection.ALL, fixture.access.cleared);
        assertFalse(fixture.processor.execute("job delete " + JOB_ID).success());
        assertTrue(fixture.processor.execute("job delete " + JOB_ID + " confirm").success());
        String query = fixture.processor.execute("cache query beatmapset 12345").message();
        assertTrue(query.contains("osuRenderer: PRESENT"));
        assertTrue(fixture.processor.execute("cache query score 12345").message().contains("N/A"));
        assertTrue(fixture.processor.execute("cache fetch replay 12345").message().contains("N/A"));
    }

    @Test
    void stopRequestsGracefulShutdown() throws InterruptedException {
        Fixture fixture = fixture();
        assertTrue(fixture.processor.execute("stop confirm").success());
        assertTrue(fixture.access.stopped.await(2, TimeUnit.SECONDS));
    }

    private Fixture fixture() {
        AppConfig config = new AppConfig(
                new RendererConfig("renderer-api-secret", "danser", new DanserRuntimeConfig(Map.of("SECRET", "value"), "", ""),
                        "data", "data/cache", 5, 2, 15, 10, null),
                new WebserverConfig(8722, 512)
        );
        FakeAccess access = new FakeAccess();
        return new Fixture(new RendererConsoleProcessor(config, access), access);
    }

    private record Fixture(RendererConsoleProcessor processor, FakeAccess access) { }

    private static final class FakeAccess implements RendererConsoleAccess {
        private final CountDownLatch stopped = new CountDownLatch(1);
        private final JobProgress job = new JobProgress(JOB_ID, JobStatus.DONE);
        private RenderAssetCache.CacheSelection cleared;
        @Override public WebServer.ServerStatus status() {
            return new WebServer.ServerStatus(true, 10, 1,
                    new ReplayRenderService.ServiceStatus(0, 0, 2, 3, 1, Map.of(JobStatus.DONE, 1L)));
        }
        @Override public List<JobProgress> listJobs() { return List.of(job); }
        @Override public JobProgress getJob(String jobId) { return job; }
        @Override public void deleteJob(String jobId) { }
        @Override public RenderAssetCache.CacheSummary cacheSummary() { return new RenderAssetCache.CacheSummary(1, 2, 1024); }
        @Override public boolean hasCachedAsset(RenderAssetCache.CacheType type, long id) { return true; }
        @Override public boolean removeCachedAsset(RenderAssetCache.CacheType type, long id) { return true; }
        @Override public int clearCache(RenderAssetCache.CacheSelection selection) { cleared = selection; return 3; }
        @Override public CacheControlResult controlCache(CacheControlRequest request) {
            String status = "FETCH".equalsIgnoreCase(request.operation())
                    || "SCORE".equals(request.type()) || "BEATMAP".equals(request.type()) ? "N/A" : "PRESENT";
            return new CacheControlResult(request.operation().toUpperCase(), request.type(), request.id(), List.of(
                    new CacheControlResult.CacheNodeResult("osuRenderer", status, null, null, null, null)
            ));
        }
        @Override public int cleanupNow() { return 0; }
        @Override public void requestStop() { stopped.countDown(); }
    }
}
