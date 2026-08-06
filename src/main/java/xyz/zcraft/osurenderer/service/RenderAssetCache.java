package xyz.zcraft.osurenderer.service;

import xyz.zcraft.osurenderer.model.CacheLookup;
import xyz.zcraft.osurenderer.model.CacheStatus;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

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
}
