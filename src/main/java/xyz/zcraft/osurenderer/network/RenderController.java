package xyz.zcraft.osurenderer.network;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import io.javalin.http.Context;
import io.javalin.http.UploadedFile;
import xyz.zcraft.osurenderer.model.JobProgress;
import xyz.zcraft.osurenderer.model.CacheLookup;
import xyz.zcraft.osurenderer.model.QueuedJob;
import xyz.zcraft.osurenderer.model.RenderRequest;
import xyz.zcraft.osurenderer.service.ReplayRenderService;
import xyz.zcraft.osurenderer.service.MissingCacheAssetException;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

public final class RenderController {
    private static final Gson GSON = new Gson();
    private static final int MAX_ASSETS_PER_REQUEST = 1000;
    private final ReplayRenderService service;

    public RenderController(ReplayRenderService service) {
        this.service = service;
    }

    public void create(Context context) throws IOException {
        RenderRequest.Mode mode = parseMode(context.formParam("mode"));
        UploadedFile configUpload = requireUpload(context, "config");
        String beatmapId = context.formParam("beatmapId");
        if (mode == RenderRequest.Mode.SHOWCASE && (beatmapId == null || beatmapId.isBlank())) {
            throw new IllegalArgumentException("beatmapId is required for showcase renders");
        }
        if (context.formParam("beatmapsetId") == null) {
            createLegacy(context, mode, beatmapId, configUpload);
            return;
        }

        long beatmapsetId = requirePositiveLong(context.formParam("beatmapsetId"), "beatmapsetId");
        List<Long> replayIds = parseIds(context.formParam("replayIds"), "replayIds", false);
        List<Long> replayUploadIds = parseIds(context.formParam("replayUploadIds"), "replayUploadIds", true);
        UploadedFile beatmapsetUpload = context.uploadedFile("beatmapset");
        List<UploadedFile> replayUploads = context.uploadedFiles("replays");
        if (replayUploads.size() != replayUploadIds.size()) {
            throw new IllegalArgumentException("replayUploadIds must match the uploaded replay files");
        }
        if (replayIds.size() > MAX_ASSETS_PER_REQUEST) {
            throw new IllegalArgumentException("A render can reference at most " + MAX_ASSETS_PER_REQUEST + " replays");
        }
        if (mode == RenderRequest.Mode.SINGLE && replayIds.size() != 1) {
            throw new IllegalArgumentException("Single renders require exactly one replay id");
        }
        if (new HashSet<>(replayIds).size() != replayIds.size()) {
            throw new IllegalArgumentException("replayIds must be unique");
        }
        if (new HashSet<>(replayUploadIds).size() != replayUploadIds.size()
                || !replayIds.containsAll(replayUploadIds)) {
            throw new IllegalArgumentException("replayUploadIds must be unique members of replayIds");
        }

        String jobId = UUID.randomUUID().toString();
        Path workspace = service.createWorkspace(jobId);
        try {
            Path config = save(configUpload, workspace.resolve("danser-config.json"));
            if (beatmapsetUpload != null) {
                if (beatmapsetUpload.size() == 0) {
                    throw new IllegalArgumentException("Uploaded beatmapset is empty");
                }
                try (InputStream input = beatmapsetUpload.content()) {
                    service.cacheBeatmapset(beatmapsetId, input);
                }
            }
            for (int i = 0; i < replayUploads.size(); i++) {
                UploadedFile upload = replayUploads.get(i);
                if (upload.size() == 0) {
                    throw new IllegalArgumentException("Uploaded replay is empty");
                }
                try (InputStream input = upload.content()) {
                    service.cacheReplay(replayUploadIds.get(i), input);
                }
            }

            if (!service.hasBeatmapset(beatmapsetId)) {
                throw new MissingCacheAssetException("Beatmapset " + beatmapsetId + " is not cached");
            }
            List<Long> missingReplays = replayIds.stream().filter(id -> !service.hasReplay(id)).toList();
            if (!missingReplays.isEmpty()) {
                throw new MissingCacheAssetException("Replays are not cached: " + missingReplays);
            }
            service.materializeBeatmapset(
                    beatmapsetId, workspace.resolve("songs").resolve(beatmapsetId + ".osz"));
            List<Path> replays = replayIds.stream().map(service::getCachedReplay).toList();

            RenderRequest request = new RenderRequest(
                    jobId,
                    mode,
                    beatmapId,
                    List.copyOf(replays),
                    config,
                    parseOptionalDouble(context.formParam("start")),
                    parseOptionalDouble(context.formParam("end")),
                    workspace);
            respondQueued(context, service.queue(request));
        } catch (Exception e) {
            ReplayRenderService.deleteTree(workspace);
            throw e;
        }
    }

    private void createLegacy(Context context, RenderRequest.Mode mode, String beatmapId,
                              UploadedFile configUpload) throws IOException {
        UploadedFile beatmapsetUpload = requireUpload(context, "beatmapset");
        List<UploadedFile> replayUploads = context.uploadedFiles("replays");
        if (replayUploads.isEmpty()) {
            throw new IllegalArgumentException("At least one replay file is required");
        }
        if (mode == RenderRequest.Mode.SINGLE && replayUploads.size() != 1) {
            throw new IllegalArgumentException("Single renders require exactly one replay file");
        }

        String jobId = UUID.randomUUID().toString();
        Path workspace = service.createWorkspace(jobId);
        try {
            Path config = save(configUpload, workspace.resolve("danser-config.json"));
            save(beatmapsetUpload, workspace.resolve("songs").resolve("beatmapset.osz"));
            List<Path> replays = new ArrayList<>();
            for (int i = 0; i < replayUploads.size(); i++) {
                replays.add(save(replayUploads.get(i),
                        workspace.resolve("replays").resolve("replay-" + i + ".osr")));
            }
            RenderRequest request = new RenderRequest(
                    jobId,
                    mode,
                    beatmapId,
                    List.copyOf(replays),
                    config,
                    parseOptionalDouble(context.formParam("start")),
                    parseOptionalDouble(context.formParam("end")),
                    workspace);
            respondQueued(context, service.queue(request));
        } catch (Exception e) {
            ReplayRenderService.deleteTree(workspace);
            throw e;
        }
    }

    private static void respondQueued(Context context, QueuedJob queued) {
        JsonObject response = new JsonObject();
        response.addProperty("id", queued.id());
        response.addProperty("status", "queued");
        response.addProperty("position", queued.position());
        context.status(202).contentType("application/json").result(response.toString());
    }

    public void overview(Context context) {
        JsonObject response = new JsonObject();
        response.addProperty("queue", service.queueSize());
        response.addProperty("active", service.activeCount());
        context.json(response);
    }

    public void cacheStatus(Context context) {
        CacheLookup lookup;
        try {
            lookup = GSON.fromJson(context.body(), CacheLookup.class);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("Invalid cache lookup body", e);
        }
        if (lookup == null) {
            throw new IllegalArgumentException("Cache lookup body is required");
        }
        if (lookup.beatmapsetIds().size() > MAX_ASSETS_PER_REQUEST
                || lookup.replayIds().size() > MAX_ASSETS_PER_REQUEST) {
            throw new IllegalArgumentException("A cache lookup can contain at most "
                    + MAX_ASSETS_PER_REQUEST + " ids of each asset type");
        }
        var status = service.getCacheStatus(lookup);
        JsonObject response = new JsonObject();
        response.add("beatmapsetIds", GSON.toJsonTree(status.beatmapsetIds()));
        response.add("replayIds", GSON.toJsonTree(status.replayIds()));
        context.contentType("application/json").result(response.toString());
    }

    public void status(Context context) {
        String jobId = context.pathParam("jobId");
        JobProgress progress = service.getProgress(jobId);
        if (progress == null) {
            context.status(404).result("Job not found");
            return;
        }
        JsonObject response = GSON.toJsonTree(progress).getAsJsonObject();
        response.addProperty("status", progress.status().name().toLowerCase(Locale.ROOT));
        context.contentType("application/json").result(response.toString());
    }

    public void video(Context context) throws IOException {
        InputStream video = service.openResult(context.pathParam("jobId"));
        if (video == null) {
            context.status(404).result("Video expired or not found");
            return;
        }
        context.contentType("video/mp4").result(video);
    }

    public void delete(Context context) throws IOException {
        service.deleteJob(context.pathParam("jobId"));
        context.status(204);
    }

    private static UploadedFile requireUpload(Context context, String name) {
        UploadedFile upload = context.uploadedFile(name);
        if (upload == null || upload.size() == 0) {
            throw new IllegalArgumentException(name + " file is required");
        }
        return upload;
    }

    private static Path save(UploadedFile upload, Path destination) throws IOException {
        Files.createDirectories(destination.getParent());
        try (InputStream input = upload.content()) {
            Files.copy(input, destination, StandardCopyOption.REPLACE_EXISTING);
        }
        return destination;
    }

    private static RenderRequest.Mode parseMode(String value) {
        if (value == null) {
            throw new IllegalArgumentException("mode is required");
        }
        try {
            return RenderRequest.Mode.valueOf(value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("mode must be single or showcase");
        }
    }

    private static double parseOptionalDouble(String value) {
        if (value == null || value.isBlank()) {
            return Double.NaN;
        }
        return Double.parseDouble(value);
    }

    private static List<Long> parseIds(String json, String fieldName, boolean optional) {
        if (json == null || json.isBlank()) {
            if (optional) {
                return List.of();
            }
            throw new IllegalArgumentException(fieldName + " is required");
        }
        try {
            Long[] values = GSON.fromJson(json, Long[].class);
            if (values == null || (!optional && values.length == 0)) {
                throw new IllegalArgumentException(fieldName + " must not be empty");
            }
            List<Long> ids = new ArrayList<>(values.length);
            for (Long value : values) {
                if (value == null || value <= 0) {
                    throw new IllegalArgumentException(fieldName + " must contain only positive ids");
                }
                ids.add(value);
            }
            return List.copyOf(ids);
        } catch (com.google.gson.JsonParseException e) {
            throw new IllegalArgumentException(fieldName + " must be a JSON array of ids", e);
        }
    }

    private static long requirePositiveLong(String value, String fieldName) {
        try {
            long parsed = Long.parseLong(value);
            if (parsed <= 0) {
                throw new NumberFormatException();
            }
            return parsed;
        } catch (NumberFormatException | NullPointerException e) {
            throw new IllegalArgumentException(fieldName + " must be a positive id");
        }
    }
}
