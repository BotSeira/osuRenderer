package xyz.zcraft.osurenderer.service;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import xyz.zcraft.osurenderer.config.RendererConfig;

import java.io.IOException;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReplayRenderServiceTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void rendererOwnsOnlyTheDeploymentSpecificSongsPath() {
        String supplied = "{\"General\":{\"OsuSongsDir\":\"{{OSU_SONGS_DIR}}\"},\"Recording\":{\"FPS\":60}}";

        String result = ReplayRenderService.applySongsPath(supplied, Path.of("work", "songs"));
        var json = JsonParser.parseString(result).getAsJsonObject();

        assertTrue(json.getAsJsonObject("General").get("OsuSongsDir").getAsString().replace('\\', '/').endsWith("work/songs"));
        assertEquals(60, json.getAsJsonObject("Recording").get("FPS").getAsInt());
    }

    @Test
    void missingGeneralSectionIsAddedWithoutChangingOtherSettings() {
        String result = ReplayRenderService.applySongsPath("{\"Recording\":{\"FPS\":30}}", Path.of("songs"));
        var json = JsonParser.parseString(result).getAsJsonObject();

        assertTrue(json.has("General"));
        assertEquals(30, json.getAsJsonObject("Recording").get("FPS").getAsInt());
    }

    @Test
    void jobIdsCannotEscapeTheManagedWorkspace() throws Exception {
        RendererConfig config = new RendererConfig(
                "",
                temporaryDirectory.resolve("danser-cli").toString(),
                temporaryDirectory.resolve("work").toString(),
                temporaryDirectory.resolve("cache").toString(),
                1,
                1,
                1,
                1);
        try (ReplayRenderService service = new ReplayRenderService(config)) {
            assertThrows(IOException.class, () -> service.deleteJob(".."));
        }
    }

    @Test
    void persistentCacheCannotBePlacedInsideTemporaryJobs() {
        Path work = temporaryDirectory.resolve("unsafe-work");
        RendererConfig config = new RendererConfig(
                "",
                temporaryDirectory.resolve("danser-cli").toString(),
                work.toString(),
                work.resolve("jobs/cache").toString(),
                1,
                1,
                1,
                1);

        assertThrows(IOException.class, () -> new ReplayRenderService(config));
    }
}
