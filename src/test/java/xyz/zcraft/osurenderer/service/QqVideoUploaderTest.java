package xyz.zcraft.osurenderer.service;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import xyz.zcraft.osurenderer.model.QqFileInfo;
import xyz.zcraft.osurenderer.model.QqUploadRequest;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QqVideoUploaderTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void uploadsLocalVideoByPartsAndReturnsQqFileInfo() throws Exception {
        Map<Integer, byte[]> uploadedParts = new ConcurrentHashMap<>();
        AtomicReference<String> authorization = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> handle(exchange, server.getAddress().getPort(),
                uploadedParts, authorization));
        server.start();

        try {
            Path video = Files.writeString(temporaryDirectory.resolve("render.mp4"), "abcdefghij");
            URI endpoint = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/");
            HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
            QqVideoUploader uploader = new QqVideoUploader(endpoint, client, client);

            QqFileInfo result = uploader.upload(video,
                    new QqUploadRequest("temporary-token", "groups", "group-open-id"));

            assertEquals("uuid-1", result.fileUuid());
            assertEquals("file-info-1", result.fileInfo());
            assertEquals(300, result.ttl());
            assertEquals("QQBot temporary-token", authorization.get());
            assertEquals("abcd", new String(uploadedParts.get(1), StandardCharsets.UTF_8));
            assertEquals("efgh", new String(uploadedParts.get(2), StandardCharsets.UTF_8));
            assertEquals("ij", new String(uploadedParts.get(3), StandardCharsets.UTF_8));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void rejectsInvalidTargetAndEmptyVideo() throws Exception {
        assertThrows(IllegalArgumentException.class,
                () -> new QqUploadRequest("token", "channels", "target"));
        Path empty = Files.createFile(temporaryDirectory.resolve("empty.mp4"));
        assertThrows(IOException.class, () -> new QqVideoUploader().upload(
                empty, new QqUploadRequest("token", "users", "user")));
    }

    private static void handle(HttpExchange exchange, int port, Map<Integer, byte[]> uploadedParts,
                               AtomicReference<String> authorization) throws IOException {
        String path = exchange.getRequestURI().getPath();
        if (path.startsWith("/part/")) {
            int index = Integer.parseInt(path.substring("/part/".length()));
            uploadedParts.put(index, exchange.getRequestBody().readAllBytes());
            respond(exchange, 200, "");
            return;
        }

        authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        if (path.endsWith("/upload_prepare")) {
            assertTrue(body.contains("\"file_size\":\"10\""));
            String response = """
                    {"data":{"upload_id":"upload-1","block_size":4,"parts":[
                    {"index":1,"presigned_url":"http://127.0.0.1:%d/part/1","block_size":4},
                    {"index":2,"presigned_url":"http://127.0.0.1:%d/part/2","block_size":4},
                    {"index":3,"presigned_url":"http://127.0.0.1:%d/part/3","block_size":4}],
                    "upload_config":{"concurrency":2,"retry_timeout":2,"retry_delay":1}}}
                    """.formatted(port, port, port);
            respond(exchange, 200, response);
        } else if (path.endsWith("/upload_part_finish")) {
            assertTrue(body.contains("\"upload_id\":\"upload-1\""));
            respond(exchange, 200, "{\"data\":{}}");
        } else if (path.endsWith("/files")) {
            assertTrue(body.contains("\"srv_send_msg\":false"));
            respond(exchange, 200,
                    "{\"data\":{\"file_uuid\":\"uuid-1\",\"file_info\":\"file-info-1\",\"ttl\":300}}");
        } else {
            respond(exchange, 404, "{}");
        }
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
