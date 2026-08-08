package xyz.zcraft.osurenderer.console;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.config.Configurator;
import xyz.zcraft.osurenderer.config.AppConfig;
import xyz.zcraft.osurenderer.config.ConfigLoader;
import xyz.zcraft.osurenderer.model.CacheControlRequest;
import xyz.zcraft.osurenderer.model.CacheControlResult;
import xyz.zcraft.osurenderer.model.JobProgress;
import xyz.zcraft.osurenderer.model.JobStatus;
import xyz.zcraft.osurenderer.network.WebServer;
import xyz.zcraft.osurenderer.service.RenderAssetCache;
import xyz.zcraft.osurenderer.service.ReplayRenderService;

import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

public final class RendererConsoleProcessor {
    private static final long STARTED_AT = System.currentTimeMillis();
    private static final List<String> ROOT = List.of(
            "help", "status", "queue", "jobs", "job", "cache", "cleanup", "config", "log", "system", "stop");
    private static final Map<String, List<String>> SUB = Map.of(
            "job", List.of("show", "delete"),
            "cache", List.of("query", "delete", "get", "fetch", "status", "has", "remove", "clear"),
            "cleanup", List.of("now"),
            "config", List.of("show", "check"),
            "log", List.of("show", "level")
    );

    private final AppConfig config;
    private final RendererConsoleAccess access;

    public RendererConsoleProcessor(AppConfig config, RendererConsoleAccess access) {
        this.config = Objects.requireNonNull(config);
        this.access = Objects.requireNonNull(access);
    }

    public Result execute(String line) {
        try {
            ConsoleInputParser.ParsedInput input = ConsoleInputParser.parse(line);
            if (input.size() == 0) return Result.ok("");
            return switch (input.value(0).toLowerCase(Locale.ROOT)) {
                case "help", "?" -> help(input);
                case "status" -> exact(input, 1, this::status, "Usage: status");
                case "queue" -> exact(input, 1, this::queue, "Usage: queue");
                case "jobs" -> jobs(input);
                case "job" -> job(input);
                case "cache" -> cache(input);
                case "cleanup" -> cleanup(input);
                case "config" -> config(input);
                case "log" -> log(input);
                case "system" -> exact(input, 1, this::system, "Usage: system");
                case "stop", "shutdown", "exit", "quit" -> stop(input);
                default -> Result.error("Unknown console command: " + input.value(0) + ". Run 'help'.");
            };
        } catch (IllegalArgumentException e) {
            return Result.error(e.getMessage());
        } catch (Exception e) {
            org.apache.logging.log4j.LogManager.getLogger(RendererConsoleProcessor.class)
                    .error("Console command failed", e);
            return Result.error("Command failed: " + rootMessage(e));
        }
    }

    static List<String> rootCommands() { return ROOT; }
    static List<String> subcommands(String command) {
        return SUB.getOrDefault(command.toLowerCase(Locale.ROOT), List.of());
    }

    private Result help(ConsoleInputParser.ParsedInput input) {
        if (input.size() > 2) return Result.error("Usage: help [command]");
        if (input.size() == 1) return Result.ok("""
                osuRenderer administration console
                  status                              Service, HTTP, queue, job, and cache health
                  queue                               Render worker and queue utilization
                  jobs [status]                       List tracked jobs, optionally filtered by status
                  job show <job-id>                   Show one job
                  job delete <job-id> confirm         Delete job metadata and result
                  cache <query|delete|get|fetch> <type> <id>
                                                       Unified cache inspection and control
                  cache status                        Show persistent cache usage
                  cache has <beatmapset|replay> <id>  Check one cached asset
                  cache remove <type> <id> confirm    Remove one cached asset
                  cache clear <type|all> confirm      Clear persistent cached assets
                  cleanup now                         Run expired-result cleanup immediately
                  config <show|check>                 Show redacted config or validate config.yml
                  log <show|level>                    Inspect or change the Log4J2 root level
                  system                              JVM, OS, thread, memory, version, and uptime
                  stop confirm                        Gracefully stop osuRenderer

                Aliases: ? (help), shutdown/exit/quit (stop)
                """.stripTrailing());
        String topic = input.value(1).toLowerCase(Locale.ROOT);
        String detail = switch (topic) {
            case "status" -> "status\nShows the web server, HTTP counters, renderer pool, jobs, and cache.";
            case "queue" -> "queue\nShows active workers, configured pool size, waiting jobs, and completed tasks.";
            case "jobs" -> "jobs [queued|rendering|uploading|done|failed|timeout]\nLists tracked jobs.";
            case "job" -> "job show <uuid>\njob delete <uuid> confirm\nDeletion removes metadata and any result file.";
            case "cache" -> "cache <query|delete|get|fetch> <score|beatmap|beatmapset|replay> <id>\nfetch must be initiated from oStella or SeiraCore because workers intentionally have no upstream credentials.\ncache status\ncache has <beatmapset|replay> <id>\ncache remove <type> <id> confirm\ncache clear <beatmapsets|replays|all> confirm";
            case "cleanup" -> "cleanup now\nRuns the configured result-TTL cleanup immediately.";
            case "config" -> "config show\nconfig check\nCredentials and environment values are never displayed.";
            case "log" -> "log show\nlog level <trace|debug|info|warn|error>";
            case "system" -> "system\nShows local runtime information and process uptime.";
            case "stop", "shutdown", "exit", "quit" -> "stop confirm\nGracefully closes JLine, Javalin, and render workers.";
            default -> null;
        };
        return detail == null ? Result.error("No help topic named '" + input.value(1) + "'.") : Result.ok(detail);
    }

    private Result status() {
        WebServer.ServerStatus status = access.status();
        ReplayRenderService.ServiceStatus renderer = status.renderer();
        RenderAssetCache.CacheSummary cache = access.cacheSummary();
        return Result.ok("""
                osuRenderer %s
                  Web server: %s (port %d)
                  HTTP requests: %d total, %d failed
                  Render pool: %d/%d active, %d queued, %d completed
                  Jobs: %d tracked
                  Cache: %d beatmapsets, %d replays, %s
                  Uptime: %s
                """.formatted(version(), status.running() ? "RUNNING" : "STOPPED", config.webserver().port(),
                status.requests(), status.failures(), renderer.active(), renderer.poolSize(), renderer.queued(),
                renderer.completed(), renderer.trackedJobs(), cache.beatmapsets(), cache.replays(),
                bytes(cache.bytes()), duration(System.currentTimeMillis() - STARTED_AT)).stripTrailing());
    }

    private Result queue() {
        ReplayRenderService.ServiceStatus value = access.status().renderer();
        return Result.ok("Active workers: %d / %d\nQueued jobs: %d / %d\nCompleted tasks: %d".formatted(
                value.active(), value.poolSize(), value.queued(), config.renderer().renderQueueSize(), value.completed()));
    }

    private Result jobs(ConsoleInputParser.ParsedInput input) {
        if (input.size() > 2) return Result.error("Usage: jobs [status]");
        JobStatus filter = input.size() == 2 ? jobStatus(input.value(1)) : null;
        List<JobProgress> jobs = access.listJobs().stream().filter(job -> filter == null || job.status() == filter).toList();
        if (jobs.isEmpty()) return Result.ok("No matching jobs.");
        StringBuilder output = new StringBuilder("Tracked jobs (" + jobs.size() + "):");
        jobs.forEach(job -> output.append("\n  ").append(formatJob(job)));
        return Result.ok(output.toString());
    }

    private Result job(ConsoleInputParser.ParsedInput input) throws IOException {
        if (input.size() == 3 && "show".equalsIgnoreCase(input.value(1))) {
            String id = uuid(input.value(2));
            JobProgress progress = access.getJob(id);
            return progress == null ? Result.error("Job not found: " + id) : Result.ok(formatJob(progress));
        }
        if (input.size() == 4 && "delete".equalsIgnoreCase(input.value(1))
                && "confirm".equalsIgnoreCase(input.value(3))) {
            String id = uuid(input.value(2));
            access.deleteJob(id);
            return Result.ok("Deleted job: " + id);
        }
        return Result.error("Usage: job <show <job-id>|delete <job-id> confirm>");
    }

    private Result cache(ConsoleInputParser.ParsedInput input) throws IOException {
        if (input.size() == 4 && List.of("query", "delete", "get", "fetch")
                .contains(input.value(1).toLowerCase(Locale.ROOT))) {
            String type = cacheControlType(input.value(2));
            long id = positiveLong(input.value(3));
            return Result.ok(formatCacheControl(access.controlCache(new CacheControlRequest(
                    input.value(1), type, id
            ))));
        }
        if (input.size() == 2 && "status".equalsIgnoreCase(input.value(1))) {
            RenderAssetCache.CacheSummary value = access.cacheSummary();
            return Result.ok("Beatmapsets: %d\nReplays: %d\nTotal size: %s".formatted(
                    value.beatmapsets(), value.replays(), bytes(value.bytes())));
        }
        if (input.size() == 4 && "has".equalsIgnoreCase(input.value(1))) {
            RenderAssetCache.CacheType type = cacheType(input.value(2));
            long id = positiveLong(input.value(3));
            return Result.ok((access.hasCachedAsset(type, id) ? "Cached: " : "Not cached: ") + typeName(type) + " " + id);
        }
        if (input.size() == 5 && "remove".equalsIgnoreCase(input.value(1))
                && "confirm".equalsIgnoreCase(input.value(4))) {
            RenderAssetCache.CacheType type = cacheType(input.value(2));
            long id = positiveLong(input.value(3));
            return Result.ok(access.removeCachedAsset(type, id)
                    ? "Removed cached " + typeName(type) + " " + id + "."
                    : "Cached asset was not found.");
        }
        if (input.size() == 4 && "clear".equalsIgnoreCase(input.value(1))
                && "confirm".equalsIgnoreCase(input.value(3))) {
            RenderAssetCache.CacheSelection selection = cacheSelection(input.value(2));
            return Result.ok("Removed " + access.clearCache(selection) + " cached file(s).");
        }
        return Result.error("Usage: cache <query|delete|get|fetch> <score|beatmap|beatmapset|replay> <id> | cache <status|has|remove|clear> [arguments]");
    }

    private Result cleanup(ConsoleInputParser.ParsedInput input) {
        return input.size() == 2 && "now".equalsIgnoreCase(input.value(1))
                ? Result.ok("Expired cleanup completed; removed " + access.cleanupNow() + " job(s).")
                : Result.error("Usage: cleanup now");
    }

    private Result config(ConsoleInputParser.ParsedInput input) {
        if (input.size() != 2) return Result.error("Usage: config <show|check>");
        if ("check".equalsIgnoreCase(input.value(1))) {
            ConfigLoader.loadConfig();
            return Result.ok("config.yml is valid. No settings were applied.");
        }
        if (!"show".equalsIgnoreCase(input.value(1))) return Result.error("Usage: config <show|check>");
        var renderer = config.renderer();
        return Result.ok("""
                Effective configuration (credentials and environment values redacted)
                  webserver.port = %d
                  webserver.maxRequestSizeMb = %d
                  renderer.apiKey = %s
                  renderer.danserPath = %s
                  renderer.workPath = %s
                  renderer.cachePath = %s
                  renderer.renderThreads = %d
                  renderer.renderQueueSize = %d
                  renderer.resultTtlMinutes = %d
                  renderer.renderTimeoutMinutes = %d
                  renderer.danserConfigPath = %s
                  renderer.danserRuntime.envVars = %d entries
                """.formatted(config.webserver().port(), config.webserver().maxRequestSizeMb(),
                renderer.apiKey().isBlank() ? "not set" : "configured (redacted)", renderer.danserPath(),
                renderer.workPath(), renderer.cachePath(), renderer.renderThreads(), renderer.renderQueueSize(),
                renderer.resultTtlMinutes(), renderer.renderTimeoutMinutes(),
                renderer.danserConfigPath() == null ? "not set" : renderer.danserConfigPath(),
                renderer.danserRuntime() == null || renderer.danserRuntime().envVars() == null
                        ? 0 : renderer.danserRuntime().envVars().size()).stripTrailing());
    }

    private Result log(ConsoleInputParser.ParsedInput input) {
        if (input.size() == 2 && "show".equalsIgnoreCase(input.value(1)))
            return Result.ok("Root log level: " + LogManager.getRootLogger().getLevel());
        if (input.size() == 3 && "level".equalsIgnoreCase(input.value(1))) {
            Level level = level(input.value(2));
            Configurator.setRootLevel(level);
            return Result.ok("Root log level changed to " + level + ".");
        }
        return Result.error("Usage: log <show|level <trace|debug|info|warn|error>>");
    }

    private Result system() {
        Runtime runtime = Runtime.getRuntime();
        long used = runtime.totalMemory() - runtime.freeMemory();
        return Result.ok("""
                System information
                  osuRenderer: %s
                  Java: %s (%s)
                  OS: %s %s
                  Processors: %d
                  Threads: %d
                  Heap: %s used / %s max
                  Uptime: %s
                """.formatted(version(), System.getProperty("java.version"), System.getProperty("java.vendor"),
                System.getProperty("os.name"), System.getProperty("os.arch"), runtime.availableProcessors(),
                Thread.getAllStackTraces().size(), bytes(used), bytes(runtime.maxMemory()),
                duration(System.currentTimeMillis() - STARTED_AT)).stripTrailing());
    }

    private Result stop(ConsoleInputParser.ParsedInput input) {
        if (input.size() != 2 || !"confirm".equalsIgnoreCase(input.value(1))) return Result.error("Usage: stop confirm");
        CompletableFuture.delayedExecutor(100, TimeUnit.MILLISECONDS).execute(access::requestStop);
        return Result.ok("Graceful shutdown requested.");
    }

    private static Result exact(ConsoleInputParser.ParsedInput input, int size,
                                java.util.function.Supplier<Result> action, String usage) {
        return input.size() == size ? action.get() : Result.error(usage);
    }

    private static String formatJob(JobProgress job) {
        StringBuilder value = new StringBuilder(job.id()).append(" | ").append(job.status());
        if (job.progress() != null) value.append(" | ").append(job.progress());
        if (job.speed() != null) value.append(" | ").append(job.speed());
        if (job.eta() != null) value.append(" | ETA ").append(job.eta());
        if (job.error() != null) value.append(" | error: ").append(job.error());
        if (job.qqFile() != null) value.append(" | QQ uploaded");
        return value.toString();
    }

    private static JobStatus jobStatus(String value) {
        try { return JobStatus.valueOf(value.toUpperCase(Locale.ROOT)); }
        catch (IllegalArgumentException e) { throw new IllegalArgumentException("Unknown job status: " + value); }
    }

    private static RenderAssetCache.CacheType cacheType(String value) {
        return switch (value.toLowerCase(Locale.ROOT)) {
            case "beatmapset", "beatmapsets" -> RenderAssetCache.CacheType.BEATMAPSET;
            case "replay", "replays" -> RenderAssetCache.CacheType.REPLAY;
            default -> throw new IllegalArgumentException("Cache type must be beatmapset or replay.");
        };
    }

    private static RenderAssetCache.CacheSelection cacheSelection(String value) {
        return switch (value.toLowerCase(Locale.ROOT)) {
            case "beatmapset", "beatmapsets" -> RenderAssetCache.CacheSelection.BEATMAPSETS;
            case "replay", "replays" -> RenderAssetCache.CacheSelection.REPLAYS;
            case "all" -> RenderAssetCache.CacheSelection.ALL;
            default -> throw new IllegalArgumentException("Cache selection must be beatmapsets, replays, or all.");
        };
    }

    private static String typeName(RenderAssetCache.CacheType type) {
        return type == RenderAssetCache.CacheType.BEATMAPSET ? "beatmapset" : "replay";
    }

    private static String cacheControlType(String value) {
        String normalized = value.toUpperCase(Locale.ROOT);
        if (!List.of("SCORE", "BEATMAP", "BEATMAPSET", "REPLAY").contains(normalized)) {
            throw new IllegalArgumentException("Cache type must be score, beatmap, beatmapset, or replay.");
        }
        return normalized;
    }

    private static String formatCacheControl(CacheControlResult result) {
        StringBuilder output = new StringBuilder(result.operation().toLowerCase(Locale.ROOT))
                .append(' ').append(result.type().toLowerCase(Locale.ROOT)).append(' ').append(result.id());
        for (CacheControlResult.CacheNodeResult node : result.nodes()) {
            output.append("\n  ").append(node.node()).append(": ").append(node.status());
            if (node.path() != null) output.append(" | path=").append(node.path());
            if (node.sizeBytes() != null) output.append(" | size=").append(bytes(node.sizeBytes()));
            if (node.modifiedAt() != null) output.append(" | modified=").append(node.modifiedAt());
            if (node.message() != null) output.append(" | ").append(node.message());
        }
        return output.toString();
    }

    private static long positiveLong(String value) {
        try { long id = Long.parseLong(value); if (id > 0) return id; } catch (NumberFormatException ignored) { }
        throw new IllegalArgumentException("ID must be a positive integer.");
    }

    private static String uuid(String value) {
        try { return UUID.fromString(value).toString(); }
        catch (IllegalArgumentException e) { throw new IllegalArgumentException("Job ID must be a UUID."); }
    }

    private static Level level(String value) {
        return switch (value.toLowerCase(Locale.ROOT)) {
            case "trace" -> Level.TRACE; case "debug" -> Level.DEBUG; case "info" -> Level.INFO;
            case "warn" -> Level.WARN; case "error" -> Level.ERROR;
            default -> throw new IllegalArgumentException("Log level must be trace, debug, info, warn, or error.");
        };
    }

    private static String bytes(long bytes) {
        double value = bytes;
        String[] units = {"B", "KiB", "MiB", "GiB", "TiB"};
        int unit = 0;
        while (value >= 1024 && unit < units.length - 1) { value /= 1024; unit++; }
        return unit == 0 ? bytes + " B" : String.format(Locale.ROOT, "%.1f %s", value, units[unit]);
    }

    private static String duration(long millis) {
        Duration value = Duration.ofMillis(Math.max(0, millis));
        long seconds = value.toSeconds(), days = seconds / 86400, hours = seconds % 86400 / 3600,
                minutes = seconds % 3600 / 60;
        return days > 0 ? "%dd %02dh %02dm".formatted(days, hours, minutes)
                : hours > 0 ? "%dh %02dm".formatted(hours, minutes)
                : "%dm %02ds".formatted(minutes, seconds % 60);
    }

    private static String version() {
        Properties properties = new Properties();
        try (InputStream input = RendererConsoleProcessor.class.getResourceAsStream("/version.properties")) {
            if (input != null) { properties.load(input); return properties.getProperty("version", "development"); }
        } catch (IOException ignored) { }
        return "development";
    }

    private static String rootMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null) current = current.getCause();
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }

    public record Result(boolean success, String message) {
        static Result ok(String message) { return new Result(true, message); }
        static Result error(String message) { return new Result(false, message); }
    }
}
