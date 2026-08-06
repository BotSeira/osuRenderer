package xyz.zcraft.osurenderer.model;

import java.nio.file.Path;
import java.util.List;

public record RenderRequest(
        String id,
        Mode mode,
        String beatmapId,
        List<Path> replays,
        Path config,
        double start,
        double end,
        Path workspace
) {
    public enum Mode {
        SINGLE,
        SHOWCASE
    }
}
