package xyz.zcraft.osurenderer.model;

import java.util.List;

public record CacheLookup(List<Long> beatmapsetIds, List<Long> replayIds) {
    public CacheLookup {
        beatmapsetIds = beatmapsetIds == null ? List.of() : List.copyOf(beatmapsetIds);
        replayIds = replayIds == null ? List.of() : List.copyOf(replayIds);
    }
}
