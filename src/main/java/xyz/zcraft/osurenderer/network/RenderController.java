package xyz.zcraft.osurenderer.network;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import io.javalin.http.Context;
import io.javalin.http.UploadedFile;
import xyz.zcraft.osurenderer.model.JobProgress;
import xyz.zcraft.osurenderer.model.QueuedJob;
import xyz.zcraft.osurenderer.model.RenderRequest;
import xyz.zcraft.osurenderer.service.ReplayRenderService;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

public final class RenderController {
    private static final Gson GSON = new Gson();
    private final ReplayRenderService service;

    public RenderController(ReplayRenderService service) {
        this.service = service;
    }

    public void create(Context context) throws IOException {
        RenderRequest.Mode mode = parseMode(context.formParam("mode"));
        UploadedFile configUpload = requireUpload(context, "config");
        UploadedFile beatmapsetUpload = requireUpload(context, "beatmapset");
        List<UploadedFile> replayUploads = context.uploadedFiles("replays");
        if (replayUploads.isEmpty()) {
            throw new IllegalArgumentException("At least one replay file is required");
        }
        if (mode == RenderRequest.Mode.SINGLE && replayUploads.size() != 1) {
            throw new IllegalArgumentException("Single renders require exactly one replay file");
        }

        String beatmapId = context.formParam("beatmapId");
        if (mode == RenderRequest.Mode.SHOWCASE && (beatmapId == null || beatmapId.isBlank())) {
            throw new IllegalArgumentException("beatmapId is required for showcase renders");
        }

        String jobId = UUID.randomUUID().toString();
        Path workspace = service.createWorkspace(jobId);
        try {
            Path config = save(configUpload, workspace.resolve("danser-config.json"));
            Path beatmapset = save(beatmapsetUpload, workspace.resolve("songs").resolve("beatmapset.osz"));
            List<Path> replays = new ArrayList<>();
            for (int i = 0; i < replayUploads.size(); i++) {
                replays.add(save(replayUploads.get(i), workspace.resolve("replays").resolve("replay-" + i + ".osr")));
            }

            RenderRequest request = new RenderRequest(
                    jobId,
                    mode,
                    beatmapId,
                    List.copyOf(replays),
                    beatmapset,
                    config,
                    parseOptionalDouble(context.formParam("start")),
                    parseOptionalDouble(context.formParam("end")),
                    workspace);
            QueuedJob queued = service.queue(request);
            JsonObject response = new JsonObject();
            response.addProperty("id", queued.id());
            response.addProperty("status", "queued");
            response.addProperty("position", queued.position());
            context.status(202).contentType("application/json").result(response.toString());
        } catch (Exception e) {
            ReplayRenderService.deleteTree(workspace);
            throw e;
        }
    }

    public void overview(Context context) {
        JsonObject response = new JsonObject();
        response.addProperty("queue", service.queueSize());
        response.addProperty("active", service.activeCount());
        context.json(response);
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
}
