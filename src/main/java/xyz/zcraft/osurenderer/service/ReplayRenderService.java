package xyz.zcraft.osurenderer.service;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import xyz.zcraft.osurenderer.config.RendererConfig;
import xyz.zcraft.osurenderer.model.*;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static xyz.zcraft.osurenderer.MiscUtil.deepMergeJson;

public final class ReplayRenderService implements Closeable {
    private static final Logger LOG = LogManager.getLogger(ReplayRenderService.class);
    private static final Logger DANSER_LOG = LogManager.getLogger("danser");
    private static final Pattern DANSER_PROGRESS_PATTERN =
            Pattern.compile("Progress: (\\d+)%, Speed: ([\\d.]+)x, ETA: (.+)");

    private final RendererConfig config;
    private final Path danserPath;
    private final Path jobsPath;
    private final RenderAssetCache assetCache;
    private final QqVideoUploader qqVideoUploader;
    private final ThreadPoolExecutor executor;
    private final ScheduledExecutorService cleanupExecutor = Executors.newSingleThreadScheduledExecutor();
    private final Map<String, JobProgress> jobs = new ConcurrentHashMap<>();
    private final Map<String, Path> results = new ConcurrentHashMap<>();
    private final Map<String, Long> touchedAt = new ConcurrentHashMap<>();

    public ReplayRenderService(RendererConfig config) throws IOException {
        this(config, new QqVideoUploader());
    }

    ReplayRenderService(RendererConfig config, QqVideoUploader qqVideoUploader) throws IOException {
        this.config = config;
        this.qqVideoUploader = qqVideoUploader;
        this.danserPath = Path.of(config.danserPath()).toAbsolutePath().normalize();
        this.jobsPath = Path.of(config.workPath()).toAbsolutePath().normalize().resolve("jobs");
        Path cachePath = Path.of(config.cachePath()).toAbsolutePath().normalize();
        if (cachePath.startsWith(jobsPath)) {
            throw new IOException("renderer.cachePath must not be inside the temporary jobs directory");
        }
        this.assetCache = new RenderAssetCache(cachePath);
        deleteTree(jobsPath);
        Files.createDirectories(jobsPath);

        int threads = config.renderThreads();
        this.executor = new ThreadPoolExecutor(
                threads,
                threads,
                0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(config.renderQueueSize()),
                new ThreadPoolExecutor.AbortPolicy());

        cleanupExecutor.scheduleAtFixedRate(this::cleanupExpiredJobs, 1, 1, TimeUnit.MINUTES);
    }

    static String applySongsPath(String configJson, Path songsPath) {
        JsonObject root = JsonParser.parseString(configJson).getAsJsonObject();
        JsonObject general;
        if (root.has("General") && root.get("General").isJsonObject()) {
            general = root.getAsJsonObject("General");
        } else {
            general = new JsonObject();
            root.add("General", general);
        }
        general.addProperty("OsuSongsDir", songsPath.toAbsolutePath().toString().replace('\\', '/'));
        return root.toString();
    }

    public static void deleteTree(Path root) {
        if (root == null || !Files.exists(root)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(root)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException e) {
                    LOG.warn("Failed to delete {}", path, e);
                }
            });
        } catch (IOException e) {
            LOG.warn("Failed to enumerate {} for deletion", root, e);
        }
    }

    public Path createWorkspace(String jobId) throws IOException {
        Path workspace = resolveWorkspace(jobId);
        Files.createDirectories(workspace.resolve("songs"));
        Files.createDirectories(workspace.resolve("replays"));
        return workspace;
    }

    public CacheStatus getCacheStatus(CacheLookup lookup) {
        return assetCache.status(lookup);
    }

    @SuppressWarnings("UnusedReturnValue")
    public Path cacheBeatmapset(long beatmapsetId, InputStream input) throws IOException {
        return assetCache.storeBeatmapset(beatmapsetId, input);
    }

    @SuppressWarnings("UnusedReturnValue")
    public Path cacheReplay(long scoreId, InputStream input) throws IOException {
        return assetCache.storeReplay(scoreId, input);
    }

    public boolean hasBeatmapset(long beatmapsetId) {
        return assetCache.hasBeatmapset(beatmapsetId);
    }

    public boolean hasReplay(long scoreId) {
        return assetCache.hasReplay(scoreId);
    }

    public Path getCachedReplay(long scoreId) {
        return assetCache.replayPath(scoreId);
    }

    @SuppressWarnings("UnusedReturnValue")
    public Path materializeBeatmapset(long beatmapsetId, Path destination) throws IOException {
        return assetCache.materializeBeatmapset(beatmapsetId, destination);
    }

    public QueuedJob queue(RenderRequest request) {
        jobs.put(request.id(), new JobProgress(request.id(), JobStatus.QUEUED));
        touch(request.id());
        try {
            executor.execute(() -> render(request));
        } catch (RejectedExecutionException e) {
            jobs.remove(request.id());
            touchedAt.remove(request.id());
            deleteTree(request.workspace());
            throw new QueueFullException();
        }
        return new QueuedJob(request.id(), Math.max(1, executor.getQueue().size()));
    }

    public int queueSize() {
        return executor.getQueue().size();
    }

    public int activeCount() {
        return executor.getActiveCount();
    }

    public ServiceStatus status() {
        EnumMap<JobStatus, Long> counts = new EnumMap<>(JobStatus.class);
        for (JobStatus value : JobStatus.values()) {
            counts.put(value, 0L);
        }
        jobs.values().forEach(job -> counts.compute(job.status(), (_, count) -> count + 1));
        return new ServiceStatus(
                executor.getQueue().size(),
                executor.getActiveCount(),
                executor.getMaximumPoolSize(),
                executor.getCompletedTaskCount(),
                jobs.size(),
                Map.copyOf(counts)
        );
    }

    public List<JobProgress> listJobs() {
        return jobs.values().stream()
                .sorted(Comparator.comparing(JobProgress::id))
                .toList();
    }

    public RenderAssetCache.CacheSummary cacheSummary() {
        return assetCache.summary();
    }

    public boolean hasCachedAsset(RenderAssetCache.CacheType type, long id) {
        return switch (type) {
            case BEATMAPSET -> hasBeatmapset(id);
            case REPLAY -> hasReplay(id);
        };
    }

    public boolean removeCachedAsset(RenderAssetCache.CacheType type, long id) throws IOException {
        return assetCache.remove(type, id);
    }

    public int clearCache(RenderAssetCache.CacheSelection selection) throws IOException {
        return assetCache.clear(selection);
    }

    public CacheControlResult controlCache(CacheControlRequest request) {
        if (request == null) throw new IllegalArgumentException("Cache control body is required");
        return assetCache.control(request.operation(), request.type(), request.id());
    }

    public CacheControlResult storeFetchedCache(String type, long id, InputStream input) throws IOException {
        return assetCache.storeFetched(type, id, input);
    }

    public int cleanupNow() {
        int before = jobs.size();
        cleanupExpiredJobs();
        return before - jobs.size();
    }

    public JobProgress getProgress(String jobId) {
        return jobs.get(jobId);
    }

    public InputStream openResult(String jobId) throws IOException {
        Path result = results.get(jobId);
        if (result == null || !Files.isRegularFile(result)) {
            return null;
        }
        touch(jobId);
        return Files.newInputStream(result);
    }

    public void deleteJob(String jobId) throws IOException {
        Path result = results.remove(jobId);
        jobs.remove(jobId);
        touchedAt.remove(jobId);
        if (result != null) {
            Files.deleteIfExists(result);
        }
        deleteTree(resolveWorkspace(jobId));
    }

    private void render(RenderRequest request) {
        update(request.id(), new JobProgress(request.id(), JobStatus.RENDERING));
        Path settingsFile = null;
        try {
            List<String> command = new ArrayList<>();
            settingsFile = prepareDanser(command, request);
            String outputName = switch (request.mode()) {
                case SINGLE -> "replay_";
                case SHOWCASE -> "showcase_";
                case AUTOPLAY -> "preview_";
            } + request.id();

            command.addAll(modeArguments(request));
            command.add("-out=" + outputName);

            Path video = runDanser(request.id(), outputName, command);
            if (video == null) {
                return;
            }
            results.put(request.id(), video);

            QqFileInfo qqFile = null;
            String uploadError = null;
            if (request.qqUpload() != null) {
                update(request.id(), new JobProgress(request.id(), JobStatus.UPLOADING));
                try {
                    qqFile = qqVideoUploader.upload(video, request.qqUpload());
                    LOG.info("Uploaded render job {} to QQ", request.id());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    uploadError = "QQ upload interrupted";
                    LOG.warn("QQ upload interrupted for render job {}", request.id(), e);
                } catch (Exception e) {
                    uploadError = e.getMessage() == null ? "QQ upload failed" : e.getMessage();
                    LOG.warn("Failed to upload render job {} to QQ; the video remains available", request.id(), e);
                }
            }
            update(request.id(), new JobProgress(
                    request.id(), JobStatus.DONE, null, null, null, uploadError, qqFile));
            LOG.info("Finished render job {}", request.id());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            fail(request.id(), "Render interrupted", e);
        } catch (Exception e) {
            fail(request.id(), e.getMessage() == null ? "Danser failed" : e.getMessage(), e);
        } finally {
            if (settingsFile != null) {
                try {
                    Files.deleteIfExists(settingsFile);
                } catch (IOException e) {
                    LOG.warn("Failed to delete temporary settings {}", settingsFile, e);
                }
            }
            deleteTree(request.workspace());
        }
    }

    static List<String> modeArguments(RenderRequest request) {
        List<String> arguments = new ArrayList<>();
        if (request.mode() == RenderRequest.Mode.SINGLE) {
            if (!Double.isNaN(request.start())) {
                arguments.add("-start=" + request.start());
            }
            if (!Double.isNaN(request.end())) {
                arguments.add("-end=" + request.end());
            }
            arguments.add("-replay=" + request.replays().getFirst().toAbsolutePath());
        } else if (request.mode() == RenderRequest.Mode.SHOWCASE) {
            arguments.add("-knockout2=" + replayList(request.replays()));
            arguments.add("-id=" + request.beatmapId());
        } else {
            if (!Double.isNaN(request.start())) {
                arguments.add("-start=" + request.start());
            }
            if (!Double.isNaN(request.end())) {
                arguments.add("-end=" + request.end());
            }
            arguments.add("-id=" + request.beatmapId());
            arguments.add("-mods=AT" + Objects.requireNonNullElse(request.mods(), ""));
        }
        return arguments;
    }

    private Path prepareDanser(List<String> command, RenderRequest request) throws IOException {
        final String e = System.getProperty("os.name").toLowerCase().contains("win")
                ? config.danserRuntime().commandPrefixWin() : config.danserRuntime().commandPrefix();

        if (e != null && !e.isBlank()) {
            command.addAll(List.of(e.split(" ")));
        }

        command.add(danserPath.toString());
        command.add("-noupdatecheck");
        command.add("-quickstart");
        command.add("-record");

        String suppliedConfig = Files.readString(request.config(), StandardCharsets.UTF_8);
        String givenConfig = applySongsPath(suppliedConfig, request.workspace().resolve("songs"));

        JsonObject configObj = JsonParser.parseString(givenConfig).getAsJsonObject();

        if (config.danserConfigPath() != null) {
            final String s = Files.readString(Path.of(config.danserConfigPath()), StandardCharsets.UTF_8);
            final JsonObject patch = JsonParser.parseString(s).getAsJsonObject();

            configObj = deepMergeJson(configObj, patch);
        }

        final String finalConfig = configObj.toString();

        Path settingsDir = danserPath.getParent().resolve("settings");
        Files.createDirectories(settingsDir);
        String profileName = "osurenderer_" + request.id().replace("-", "");
        Path settingsFile = settingsDir.resolve(profileName + ".json");
        Files.writeString(settingsFile, finalConfig, StandardCharsets.UTF_8);
        command.add("-settings=" + profileName);
        return settingsFile;
    }

    private static String replayList(List<Path> replayPaths) {
        String value = replayPaths.stream()
                .map(path -> "\"" + path.toAbsolutePath().toString().replace("\\", "/") + "\"")
                .reduce((left, right) -> left + "," + right)
                .map(joined -> "[" + joined + "]")
                .orElseThrow();
        if (System.getProperty("os.name").toLowerCase().contains("win")) {
            return value.replace("\"", "\\\"");
        }
        return value;
    }

    private Path runDanser(String jobId, String outputName, List<String> command)
            throws IOException, InterruptedException {
        Path video = danserPath.getParent().resolve("videos").resolve(outputName + ".mp4").normalize();
        Files.createDirectories(video.getParent());
        Files.deleteIfExists(video);

        LOG.info("Starting render job {}", jobId);
        final ProcessBuilder processBuilder = new ProcessBuilder(command);

        final Map<String, String> m = config.danserRuntime().envVars();
        if (m != null) processBuilder.environment().putAll(m);

        Process process = processBuilder.redirectErrorStream(true).start();

        consumeDanserOutput(process.getInputStream(), jobId);

        boolean finished = process.waitFor(config.renderTimeoutMinutes(), TimeUnit.MINUTES);
        if (!finished) {
            process.destroyForcibly();
            update(jobId, new JobProgress(jobId, JobStatus.TIMEOUT, null, null, null,
                    "Render timed out", null));
            LOG.error("Render job {} timed out", jobId);
            return null;
        }
        if (process.exitValue() != 0 || !Files.isRegularFile(video)) {
            throw new IOException("Danser exited with code " + process.exitValue() + " without a video");
        }

        return video;
    }

    private void consumeDanserOutput(InputStream input, String jobId) {
        Thread.ofVirtual().name("danser-output-" + jobId).start(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    Matcher matcher = DANSER_PROGRESS_PATTERN.matcher(line);
                    if (matcher.find()) {
                        update(jobId, new JobProgress(
                                jobId,
                                JobStatus.RENDERING,
                                matcher.group(1) + "%",
                                matcher.group(2) + "x",
                                matcher.group(3),
                                null,
                                null));
                    }
                    DANSER_LOG.info(line);
                }
            } catch (IOException e) {
                DANSER_LOG.warn("Failed to consume Danser output for {}", jobId, e);
            }
        });
    }

    private void fail(String jobId, String message, Exception error) {
        update(jobId, new JobProgress(jobId, JobStatus.FAILED, null, null, null, message, null));
        LOG.error("Render job {} failed", jobId, error);
    }

    private void update(String jobId, JobProgress progress) {
        jobs.put(jobId, progress);
        touch(jobId);
    }

    private void touch(String jobId) {
        touchedAt.put(jobId, System.currentTimeMillis());
    }

    private void cleanupExpiredJobs() {
        long cutoff = System.currentTimeMillis() - TimeUnit.MINUTES.toMillis(config.resultTtlMinutes());
        for (Map.Entry<String, Long> entry : touchedAt.entrySet()) {
            if (entry.getValue() >= cutoff) {
                continue;
            }
            JobProgress progress = jobs.get(entry.getKey());
            if (progress != null && (progress.status() == JobStatus.QUEUED
                    || progress.status() == JobStatus.RENDERING
                    || progress.status() == JobStatus.UPLOADING)) {
                continue;
            }
            try {
                deleteJob(entry.getKey());
            } catch (IOException e) {
                LOG.warn("Failed to clean expired job {}", entry.getKey(), e);
            }
        }
    }

    private Path resolveWorkspace(String jobId) throws IOException {
        try {
            UUID.fromString(jobId);
        } catch (IllegalArgumentException e) {
            throw new IOException("Invalid job id", e);
        }
        Path workspace = jobsPath.resolve(jobId).normalize();
        if (!workspace.getParent().equals(jobsPath)) {
            throw new IOException("Invalid job id");
        }
        return workspace;
    }

    @Override
    public void close() {
        executor.shutdownNow();
        cleanupExecutor.shutdownNow();
    }

    public record ServiceStatus(
            int queued,
            int active,
            int poolSize,
            long completed,
            int trackedJobs,
            Map<JobStatus, Long> jobsByStatus
    ) {
    }
}
