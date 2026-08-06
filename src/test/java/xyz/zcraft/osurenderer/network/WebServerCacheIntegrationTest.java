package xyz.zcraft.osurenderer.network;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import xyz.zcraft.osurenderer.config.AppConfig;
import xyz.zcraft.osurenderer.config.RendererConfig;
import xyz.zcraft.osurenderer.config.WebserverConfig;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WebServerCacheIntegrationTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void uploadedAssetsPopulateCacheAndCanBeReferencedWithoutASecondUpload() throws Exception {
        int port = freePort();
        RendererConfig renderer = new RendererConfig(
                "",
                temporaryDirectory.resolve("missing-danser").toString(),
                temporaryDirectory.resolve("work").toString(),
                temporaryDirectory.resolve("cache").toString(),
                5,
                1,
                1,
                1);
        WebServer server = new WebServer(new AppConfig(renderer, new WebserverConfig(port, 16)));
        server.start();

        try (HttpClient client = HttpClient.newHttpClient()) {
            HttpResponse<String> health = client.send(HttpRequest.newBuilder(
                            URI.create("http://127.0.0.1:" + port + "/health")).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, health.statusCode());
            assertEquals("{\"ok\":true}", health.body());

            HttpResponse<String> overview = client.send(HttpRequest.newBuilder(
                            URI.create("http://127.0.0.1:" + port + "/renders/status")).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, overview.statusCode());
            assertTrue(overview.body().contains("\"queue\""));

            URI renders = URI.create("http://127.0.0.1:" + port + "/renders");
            HttpResponse<String> first = client.send(multipartRequest(renders, true),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(202, first.statusCode());

            HttpRequest lookup = HttpRequest.newBuilder(
                            URI.create("http://127.0.0.1:" + port + "/cache/status"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(
                            "{\"beatmapsetIds\":[123],\"replayIds\":[456]}"))
                    .build();
            HttpResponse<String> cached = client.send(lookup, HttpResponse.BodyHandlers.ofString());
            assertEquals(200, cached.statusCode());
            assertTrue(cached.body().contains("123"));
            assertTrue(cached.body().contains("456"));

            HttpResponse<String> second = client.send(multipartRequest(renders, false),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(202, second.statusCode());
        } finally {
            server.close();
        }
    }

    private static HttpRequest multipartRequest(URI uri, boolean includeCacheMisses) throws IOException {
        String boundary = "test-" + UUID.randomUUID();
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        text(body, boundary, "mode", "single");
        text(body, boundary, "beatmapsetId", "123");
        text(body, boundary, "replayIds", "[456]");
        text(body, boundary, "replayUploadIds", includeCacheMisses ? "[456]" : "[]");
        file(body, boundary, "config", "config.json", "application/json", "{}".getBytes(StandardCharsets.UTF_8));
        if (includeCacheMisses) {
            file(body, boundary, "beatmapset", "123.osz", "application/octet-stream",
                    "beatmapset".getBytes(StandardCharsets.UTF_8));
            file(body, boundary, "replays", "456.osr", "application/octet-stream",
                    "replay".getBytes(StandardCharsets.UTF_8));
        }
        body.write(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        return HttpRequest.newBuilder(uri)
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray()))
                .build();
    }

    private static void text(ByteArrayOutputStream body, String boundary, String name, String value)
            throws IOException {
        body.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + name
                + "\"\r\n\r\n" + value + "\r\n").getBytes(StandardCharsets.UTF_8));
    }

    private static void file(ByteArrayOutputStream body, String boundary, String name,
                             String filename, String contentType, byte[] value) throws IOException {
        body.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + name
                + "\"; filename=\"" + filename + "\"\r\nContent-Type: " + contentType
                + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        body.write(value);
        body.write("\r\n".getBytes(StandardCharsets.UTF_8));
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
