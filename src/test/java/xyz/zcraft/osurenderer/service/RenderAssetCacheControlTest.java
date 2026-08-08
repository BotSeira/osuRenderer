package xyz.zcraft.osurenderer.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class RenderAssetCacheControlTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void queryGetDeleteAndFetchedStorageUseUnifiedSemantics() throws Exception {
        RenderAssetCache cache = new RenderAssetCache(temporaryDirectory);
        assertEquals("MISSING", cache.control("query", "beatmapset", 123).nodes().getFirst().status());

        var fetched = cache.storeFetched("beatmapset", 123, new ByteArrayInputStream(new byte[]{1, 2, 3}));
        assertEquals("FETCHED", fetched.nodes().getFirst().status());
        assertEquals("PRESENT", cache.control("query", "beatmapset", 123).nodes().getFirst().status());
        var metadata = cache.control("get", "beatmapset", 123).nodes().getFirst();
        assertEquals(3L, metadata.sizeBytes());
        assertNotNull(metadata.modifiedAt());
        assertEquals("DELETED", cache.control("delete", "beatmapset", 123).nodes().getFirst().status());
        assertEquals("N/A", cache.control("fetch", "replay", 456).nodes().getFirst().status());
        assertEquals("N/A", cache.control("query", "score", 789).nodes().getFirst().status());
    }
}
