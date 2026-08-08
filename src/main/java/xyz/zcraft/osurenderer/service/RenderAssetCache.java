package xyz.zcraft.osurenderer.service;

import xyz.zcraft.osurenderer.model.CacheLookup;
import xyz.zcraft.osurenderer.model.CacheStatus;
import xyz.zcraft.osurenderer.model.CacheControlResult;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public final class RenderAssetCache {
    private final Path beatmapsetsPath;
    private final Path replaysPath;

    public RenderAssetCache(Path cachePath) throws IOException {
        Path root = cachePath.toAbsolutePath().normalize();
        this.beatmapsetsPath = root.resolve("beatmapsets");
        this.replaysPath = root.resolve("replays");
        Files.createDirectories(beatmapsetsPath);
        Files.createDirectories(replaysPath);
    }

    private static Path store(Path destination, InputStream input) throws IOException {
        Files.createDirectories(destination.getParent());
        Path temporary = destination.getParent().resolve(
                "." + destination.getFileName() + "." + UUID.randomUUID() + ".tmp");
        try {
            Files.copy(input, temporary, StandardCopyOption.REPLACE_EXISTING);
            if (Files.size(temporary) == 0) {
                throw new IOException("Refusing to cache an empty file");
            }
            try {
                return Files.move(temporary, destination,
                        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                return Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static boolean validFile(Path path) {
        try {
            return Files.isRegularFile(path) && Files.size(path) > 0;
        } catch (IOException e) {
            return false;
        }
    }

    private static long requireId(long id) {
        if (id <= 0) {
            throw new IllegalArgumentException("Cache ids must be positive");
        }
        return id;
    }

    public CacheStatus status(CacheLookup lookup) {
        Set<Long> beatmapsets = lookup.beatmapsetIds().stream()
                .map(RenderAssetCache::requireId)
                .filter(this::hasBeatmapset)
                .collect(Collectors.toUnmodifiableSet());
        Set<Long> replays = lookup.replayIds().stream()
                .map(RenderAssetCache::requireId)
                .filter(this::hasReplay)
                .collect(Collectors.toUnmodifiableSet());
        return new CacheStatus(beatmapsets, replays);
    }

    public boolean hasBeatmapset(long beatmapsetId) {
        return validFile(beatmapsetPath(beatmapsetId));
    }

    public boolean hasReplay(long scoreId) {
        return validFile(replayPath(scoreId));
    }

    public Path beatmapsetPath(long beatmapsetId) {
        return beatmapsetsPath.resolve(requireId(beatmapsetId) + ".osz");
    }

    public Path replayPath(long scoreId) {
        return replaysPath.resolve(requireId(scoreId) + ".osr");
    }

    public Path storeBeatmapset(long beatmapsetId, InputStream input) throws IOException {
        return store(beatmapsetPath(beatmapsetId), input);
    }

    public Path storeReplay(long scoreId, InputStream input) throws IOException {
        return store(replayPath(scoreId), input);
    }

    public Path materializeBeatmapset(long beatmapsetId, Path destination) throws IOException {
        Path source = beatmapsetPath(beatmapsetId);
        if (!validFile(source)) {
            throw new IOException("Beatmapset " + beatmapsetId + " is not cached");
        }
        Files.createDirectories(destination.getParent());
        Files.deleteIfExists(destination);
        try {
            return Files.createLink(destination, source);
        } catch (UnsupportedOperationException | IOException e) {
            return Files.copy(source, destination, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    public CacheSummary summary() {
        try {
            DirectorySummary beatmapsets = summarize(beatmapsetsPath);
            DirectorySummary replays = summarize(replaysPath);
            return new CacheSummary(
                    beatmapsets.files(), replays.files(), beatmapsets.bytes() + replays.bytes()
            );
        } catch (IOException e) {
            throw new IllegalStateException("Failed to inspect render cache", e);
        }
    }

    public boolean remove(CacheType type, long id) throws IOException {
        return Files.deleteIfExists(switch (type) {
            case BEATMAPSET -> beatmapsetPath(id);
            case REPLAY -> replayPath(id);
        });
    }

    public int clear(CacheSelection selection) throws IOException {
        int removed = 0;
        if (selection == CacheSelection.BEATMAPSETS || selection == CacheSelection.ALL) {
            removed += clearDirectory(beatmapsetsPath);
        }
        if (selection == CacheSelection.REPLAYS || selection == CacheSelection.ALL) {
            removed += clearDirectory(replaysPath);
        }
        return removed;
    }

    public CacheControlResult control(String operationValue, String typeValue, long id) {
        if (id <= 0) throw new IllegalArgumentException("Cache id must be positive");
        String operation = normalizeOperation(operationValue);
        String type = normalizeType(typeValue);
        if (!"BEATMAPSET".equals(type) && !"REPLAY".equals(type)) {
            return new CacheControlResult(operation, type, id, List.of(new CacheControlResult.CacheNodeResult(
                    "osuRenderer", "N/A", null, null, null,
                    "osuRenderer only caches beatmapsets and replays"
            )));
        }
        if ("FETCH".equals(operation)) {
            return new CacheControlResult(operation, type, id, List.of(new CacheControlResult.CacheNodeResult(
                    "osuRenderer", "N/A", null, null, null,
                    "Fetch must be initiated from oStella or SeiraCore because workers have no upstream credentials"
            )));
        }
        Path path = "BEATMAPSET".equals(type) ? beatmapsetPath(id) : replayPath(id);
        try {
            boolean exists = validFile(path);
            if ("DELETE".equals(operation)) {
                boolean deleted = Files.deleteIfExists(path);
                return result(operation, type, id, deleted ? "DELETED" : "MISSING", path,
                        null, null, null);
            }
            if (!exists) return result(operation, type, id, "MISSING", path, null, null, null);
            if ("QUERY".equals(operation)) return result(operation, type, id, "PRESENT", path, null, null, null);
            return result(operation, type, id, "PRESENT", path, Files.size(path),
                    Files.getLastModifiedTime(path).toInstant().toString(), null);
        } catch (IOException e) {
            return result(operation, type, id, "ERROR", path, null, null, e.getMessage());
        }
    }

    public CacheControlResult storeFetched(String typeValue, long id, InputStream input) throws IOException {
        String type = normalizeType(typeValue);
        if ("BEATMAPSET".equals(type)) {
            storeBeatmapset(id, input);
        } else if ("REPLAY".equals(type)) {
            storeReplay(id, input);
        } else {
            throw new IllegalArgumentException("osuRenderer can only receive fetched beatmapsets and replays");
        }
        CacheControlResult metadata = control("GET", type, id);
        CacheControlResult.CacheNodeResult node = metadata.nodes().getFirst();
        return new CacheControlResult("FETCH", type, id, List.of(new CacheControlResult.CacheNodeResult(
                node.node(), "FETCHED", node.path(), node.sizeBytes(), node.modifiedAt(), null
        )));
    }

    private CacheControlResult result(String operation, String type, long id, String status, Path path,
                                      Long size, String modifiedAt, String message) {
        Path root = beatmapsetsPath.getParent();
        String relative = root.relativize(path.toAbsolutePath().normalize()).toString().replace('\\', '/');
        return new CacheControlResult(operation, type, id, List.of(new CacheControlResult.CacheNodeResult(
                "osuRenderer", status, relative, size, modifiedAt, message
        )));
    }

    private static String normalizeOperation(String value) {
        String normalized = value == null ? "" : value.toUpperCase(Locale.ROOT);
        if (!List.of("QUERY", "GET", "DELETE", "FETCH").contains(normalized)) {
            throw new IllegalArgumentException("Cache operation must be query, get, delete, or fetch");
        }
        return normalized;
    }

    private static String normalizeType(String value) {
        String normalized = value == null ? "" : value.toUpperCase(Locale.ROOT);
        if (!List.of("SCORE", "BEATMAP", "BEATMAPSET", "REPLAY").contains(normalized)) {
            throw new IllegalArgumentException("Cache type must be score, beatmap, beatmapset, or replay");
        }
        return normalized;
    }

    private static DirectorySummary summarize(Path directory) throws IOException {
        long files = 0;
        long bytes = 0;
        try (Stream<Path> paths = Files.list(directory)) {
            for (Path path : paths.filter(Files::isRegularFile).toList()) {
                files++;
                bytes += Files.size(path);
            }
        }
        return new DirectorySummary(files, bytes);
    }

    private static int clearDirectory(Path directory) throws IOException {
        int removed = 0;
        try (Stream<Path> paths = Files.list(directory)) {
            for (Path path : paths.filter(Files::isRegularFile).toList()) {
                if (Files.deleteIfExists(path)) {
                    removed++;
                }
            }
        }
        return removed;
    }

    public enum CacheType {
        BEATMAPSET,
        REPLAY
    }

    public enum CacheSelection {
        BEATMAPSETS,
        REPLAYS,
        ALL
    }

    public record CacheSummary(long beatmapsets, long replays, long bytes) {
    }

    private record DirectorySummary(long files, long bytes) {
    }
}
