package xyz.zcraft.osurenderer.model;

import java.util.Set;

public record CacheStatus(Set<Long> beatmapsetIds, Set<Long> replayIds) {
    public CacheStatus {
        beatmapsetIds = Set.copyOf(beatmapsetIds);
        replayIds = Set.copyOf(replayIds);
    }
}
