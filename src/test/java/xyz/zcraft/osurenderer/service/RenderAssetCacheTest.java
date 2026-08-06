package xyz.zcraft.osurenderer.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import xyz.zcraft.osurenderer.model.CacheLookup;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RenderAssetCacheTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void storesAndReportsBeatmapsetsAndReplaysByStableIds() throws Exception {
        RenderAssetCache cache = new RenderAssetCache(temporaryDirectory.resolve("cache"));
        cache.storeBeatmapset(123, bytes("beatmapset"));
        cache.storeReplay(456, bytes("replay"));

        var status = cache.status(new CacheLookup(List.of(123L, 999L), List.of(456L, 888L)));

        assertEquals(java.util.Set.of(123L), status.beatmapsetIds());
        assertEquals(java.util.Set.of(456L), status.replayIds());
        assertFalse(cache.hasReplay(888));
    }

    @Test
    void materializesCachedBeatmapsetWithoutMutatingTheCache() throws Exception {
        RenderAssetCache cache = new RenderAssetCache(temporaryDirectory.resolve("cache"));
        cache.storeBeatmapset(123, bytes("beatmapset"));

        Path materialized = cache.materializeBeatmapset(123, temporaryDirectory.resolve("job/songs/123.osz"));

        assertEquals("beatmapset", Files.readString(materialized));
        Files.delete(materialized);
        assertTrue(cache.hasBeatmapset(123));
    }

    @Test
    void rejectsEmptyOrInvalidCacheEntries() throws Exception {
        RenderAssetCache cache = new RenderAssetCache(temporaryDirectory.resolve("cache"));

        assertThrows(IOException.class, () -> cache.storeReplay(456, bytes("")));
        assertThrows(IllegalArgumentException.class, () -> cache.replayPath(0));
        assertFalse(cache.hasReplay(456));
    }

    private static ByteArrayInputStream bytes(String value) {
        return new ByteArrayInputStream(value.getBytes(StandardCharsets.UTF_8));
    }
}
