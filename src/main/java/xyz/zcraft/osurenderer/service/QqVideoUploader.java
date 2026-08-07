package xyz.zcraft.osurenderer.service;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import xyz.zcraft.osurenderer.model.QqFileInfo;
import xyz.zcraft.osurenderer.model.QqUploadRequest;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.*;

public final class QqVideoUploader {
    private static final URI DEFAULT_ENDPOINT = URI.create("https://api.sgroup.qq.com/");
    private static final long MD5_10M_SIZE = 10_002_432L;
    private static final long MAX_MEDIA_SIZE = 200L * 1024 * 1024;
    private static final Gson GSON = new Gson();

    private final URI endpoint;
    private final HttpClient apiClient;
    private final HttpClient mediaClient;

    public QqVideoUploader() {
        this(DEFAULT_ENDPOINT,
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build(),
                HttpClient.newBuilder()
                        .followRedirects(HttpClient.Redirect.NORMAL)
                        .connectTimeout(Duration.ofSeconds(30))
                        .build());
    }

    QqVideoUploader(URI endpoint, HttpClient apiClient, HttpClient mediaClient) {
        this.endpoint = endpoint;
        this.apiClient = apiClient;
        this.mediaClient = mediaClient;
    }

    private static MediaDigests calculateDigests(Path file) throws IOException {
        MessageDigest md5 = newDigest("MD5");
        MessageDigest sha1 = newDigest("SHA-1");
        MessageDigest md5First10m = newDigest("MD5");
        long first10mBytes = 0;
        try (InputStream input = Files.newInputStream(file)) {
            byte[] buffer = new byte[1024 * 1024];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read == 0) continue;
                md5.update(buffer, 0, read);
                sha1.update(buffer, 0, read);
                if (first10mBytes < MD5_10M_SIZE) {
                    int digestLength = (int) Math.min(read, MD5_10M_SIZE - first10mBytes);
                    md5First10m.update(buffer, 0, digestLength);
                    first10mBytes += digestLength;
                }
            }
        }
        return new MediaDigests(HexFormat.of().formatHex(md5.digest()),
                HexFormat.of().formatHex(sha1.digest()),
                HexFormat.of().formatHex(md5First10m.digest()));
    }

    private static byte[] readPart(Path file, long defaultBlockSize, UploadPart part) throws IOException {
        long offset = Math.multiplyExact((long) part.index() - 1, defaultBlockSize);
        long requestedSize = part.blockSize() > 0 ? part.blockSize() : defaultBlockSize;
        int size = Math.toIntExact(Math.min(requestedSize, Files.size(file) - offset));
        if (size <= 0) throw new IOException("Invalid video part range: index=" + part.index());
        ByteBuffer buffer = ByteBuffer.allocate(size);
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
            while (buffer.hasRemaining()) {
                int read = channel.read(buffer, offset + buffer.position());
                if (read < 0) throw new IOException("Unexpected end of video part " + part.index());
            }
        }
        return buffer.array();
    }

    private static MessageDigest newDigest(String algorithm) {
        try {
            return MessageDigest.getInstance(algorithm);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("Missing digest algorithm " + algorithm, e);
        }
    }

    @SuppressWarnings("SameParameterValue")
    private static String requiredString(JsonObject object, String name, String action) {
        if (!object.has(name) || object.get(name).isJsonNull() || object.get(name).getAsString().isBlank()) {
            throw new IllegalArgumentException("Failed to " + action + ": missing " + name);
        }
        return object.get(name).getAsString();
    }

    public QqFileInfo upload(Path video, QqUploadRequest request) throws IOException, InterruptedException {
        long fileSize = Files.size(video);
        if (!Files.isRegularFile(video) || fileSize == 0 || fileSize > MAX_MEDIA_SIZE) {
            throw new IOException("Video size must be between 1 byte and 200 MB: " + fileSize);
        }

        MediaDigests digests = calculateDigests(video);
        UploadPrepare prepare = prepareUpload(request, fileSize, digests);
        uploadParts(request, video, prepare);
        return completeUpload(request, prepare.uploadId());
    }

    private UploadPrepare prepareUpload(QqUploadRequest request, long fileSize, MediaDigests digests)
            throws IOException, InterruptedException {
        JsonObject payload = new JsonObject();
        payload.addProperty("file_type", 2);
        payload.addProperty("file_size", Long.toString(fileSize));
        payload.addProperty("file_name", "replay.mp4");
        payload.addProperty("md5", digests.md5());
        payload.addProperty("sha1", digests.sha1());
        payload.addProperty("md5_10m", digests.md5First10m());

        JsonObject data = sendJson(request, "upload_prepare", payload);
        String uploadId = requiredString(data, "upload_id", "prepare video upload");
        long blockSize = data.has("block_size") ? data.get("block_size").getAsLong() : 5L * 1024 * 1024;

        List<UploadPart> parts = new ArrayList<>();
        if (data.has("parts") && data.get("parts").isJsonArray()) {
            data.getAsJsonArray("parts").forEach(element -> {
                JsonObject part = element.getAsJsonObject();
                parts.add(new UploadPart(
                        part.get("index").getAsInt(),
                        requiredString(part, "presigned_url", "prepare video upload"),
                        part.has("block_size") ? part.get("block_size").getAsLong() : blockSize));
            });
        }
        parts.sort(Comparator.comparingInt(UploadPart::index));

        JsonObject uploadConfig = data.has("upload_config") && data.get("upload_config").isJsonObject()
                ? data.getAsJsonObject("upload_config") : new JsonObject();
        int concurrency = uploadConfig.has("concurrency") ? uploadConfig.get("concurrency").getAsInt() : 1;
        int retryTimeout = uploadConfig.has("retry_timeout") ? uploadConfig.get("retry_timeout").getAsInt() : 300;
        int retryDelay = uploadConfig.has("retry_delay") ? uploadConfig.get("retry_delay").getAsInt() : 1;
        return new UploadPrepare(uploadId, blockSize, List.copyOf(parts), Math.max(1, concurrency),
                Math.max(1, retryTimeout), Math.max(1, retryDelay));
    }

    private void uploadParts(QqUploadRequest request, Path video, UploadPrepare prepare)
            throws IOException, InterruptedException {
        if (prepare.parts().isEmpty()) {
            return;
        }
        int workers = Math.min(prepare.concurrency(), prepare.parts().size());
        try (ExecutorService executor = Executors.newFixedThreadPool(workers)) {
            List<Callable<Void>> tasks = prepare.parts().stream().<Callable<Void>>map(part -> () -> {
                uploadPartWithRetry(request, video, prepare, part);
                return null;
            }).toList();
            List<Future<Void>> futures = executor.invokeAll(tasks);
            for (Future<Void> future : futures) {
                try {
                    future.get();
                } catch (ExecutionException e) {
                    throw new IOException("Failed to upload a video part", e.getCause());
                }
            }
        }
    }

    private void uploadPartWithRetry(QqUploadRequest request, Path video, UploadPrepare prepare, UploadPart part)
            throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(prepare.retryTimeoutSeconds());
        Exception lastFailure;
        do {
            try {
                byte[] content = readPart(video, prepare.blockSize(), part);
                HttpRequest put = HttpRequest.newBuilder(URI.create(part.presignedUrl()))
                        .timeout(Duration.ofMinutes(5))
                        .PUT(HttpRequest.BodyPublishers.ofByteArray(content))
                        .build();
                HttpResponse<String> response = mediaClient.send(put, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    throw new IOException("Part PUT failed: index=" + part.index()
                            + " status=" + response.statusCode());
                }
                finishPart(request, prepare.uploadId(), part.index(), content);
                return;
            } catch (IOException | RuntimeException e) {
                lastFailure = e;
                if (System.nanoTime() >= deadline) {
                    break;
                }
                //noinspection BusyWait
                Thread.sleep(TimeUnit.SECONDS.toMillis(prepare.retryDelaySeconds()));
            }
        } while (System.nanoTime() < deadline);
        throw new IOException("Video part upload retry timeout: index=" + part.index(), lastFailure);
    }

    private void finishPart(QqUploadRequest request, String uploadId, int partIndex, byte[] content)
            throws IOException, InterruptedException {
        JsonObject payload = new JsonObject();
        payload.addProperty("upload_id", uploadId);
        payload.addProperty("part_index", partIndex);
        payload.addProperty("block_size", Integer.toString(content.length));
        payload.addProperty("md5", HexFormat.of().formatHex(newDigest("MD5").digest(content)));
        sendJson(request, "upload_part_finish", payload);
    }

    private QqFileInfo completeUpload(QqUploadRequest request, String uploadId)
            throws IOException, InterruptedException {
        JsonObject payload = new JsonObject();
        payload.addProperty("file_type", 2);
        payload.addProperty("srv_send_msg", false);
        payload.addProperty("file_name", "replay.mp4");
        payload.addProperty("upload_id", uploadId);
        JsonObject data = sendJson(request, "files", payload);
        try {
            return GSON.fromJson(data, QqFileInfo.class);
        } catch (RuntimeException e) {
            throw new IOException("Invalid QQ upload completion response", e);
        }
    }

    private JsonObject sendJson(QqUploadRequest request, String action, JsonObject payload)
            throws IOException, InterruptedException {
        String targetId = URLEncoder.encode(request.targetId(), StandardCharsets.UTF_8).replace("+", "%20");
        URI uri = endpoint.resolve("v2/" + request.targetType() + "/" + targetId + "/" + action);
        HttpRequest httpRequest = HttpRequest.newBuilder(uri)
                .header("Authorization", "QQBot " + request.accessToken())
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .timeout(Duration.ofSeconds(30))
                .POST(HttpRequest.BodyPublishers.ofString(payload.toString()))
                .build();
        HttpResponse<String> response = apiClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("QQ " + action + " failed with HTTP " + response.statusCode());
        }
        try {
            JsonObject root = JsonParser.parseString(response.body()).getAsJsonObject();
            return root.has("data") && root.get("data").isJsonObject()
                    ? root.getAsJsonObject("data") : root;
        } catch (RuntimeException e) {
            throw new IOException("QQ " + action + " returned invalid JSON", e);
        }
    }

    private record MediaDigests(String md5, String sha1, String md5First10m) {
    }

    private record UploadPart(int index, String presignedUrl, long blockSize) {
    }

    private record UploadPrepare(String uploadId, long blockSize, List<UploadPart> parts, int concurrency,
                                 int retryTimeoutSeconds, int retryDelaySeconds) {
    }
}
