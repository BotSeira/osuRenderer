package xyz.zcraft.osurenderer.model;

public record JobProgress(
        String id,
        JobStatus status,
        String progress,
        String speed,
        String eta,
        String error
) {
    public JobProgress(String id, JobStatus status) {
        this(id, status, null, null, null, null);
    }
}
