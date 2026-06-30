package tech.skullprogrammer.engine;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

@Slf4j
public class ModelDownloader {

    // localFileName → remotePath (relative to baseUrl)
    // model.onnx is under onnx/ subfolder in sentence-transformers namespace
    private static final Map<String, String> MODEL_FILES = new LinkedHashMap<>();
    static {
        MODEL_FILES.put("model.onnx",              "onnx/model.onnx");
        MODEL_FILES.put("tokenizer.json",          "tokenizer.json");
        MODEL_FILES.put("tokenizer_config.json",   "tokenizer_config.json");
        MODEL_FILES.put("special_tokens_map.json", "special_tokens_map.json");
    }

    public static void downloadIfNeeded(Path modelDir, String baseUrl) {
        Path modelFile = modelDir.resolve("model.onnx");
        if (Files.exists(modelFile)) {
            log.info("[FakerSuggestion] Model already present at {}", modelDir);
            return;
        }

        try {
            Files.createDirectories(modelDir);
        } catch (IOException e) {
            throw new RuntimeException("Cannot create model directory: " + modelDir, e);
        }

        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .followRedirects(HttpClient.Redirect.ALWAYS)
                .build();

        String base = baseUrl.endsWith("/") ? baseUrl : baseUrl + "/";
        log.warn("[FakerSuggestion] Starting model download to {} (~225MB)...", modelDir);

        for (Map.Entry<String, String> entry : MODEL_FILES.entrySet()) {
            String fileName = entry.getKey();
            String remotePath = entry.getValue();
            Path target = modelDir.resolve(fileName);
            Path tmp = modelDir.resolve(fileName + ".tmp");
            String url = base + remotePath;

            try {
                log.info("[FakerSuggestion] Downloading {}...", fileName);
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .timeout(Duration.ofMinutes(10))
                        .GET()
                        .build();

                HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
                if (response.statusCode() != 200) {
                    throw new RuntimeException("HTTP " + response.statusCode() + " for " + url);
                }

                try (InputStream in = response.body()) {
                    Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
                }
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
                log.info("[FakerSuggestion] Downloaded {}", fileName);

            } catch (Exception e) {
                try { Files.deleteIfExists(tmp); } catch (IOException ignored) {}
                throw new RuntimeException("Failed to download " + fileName + " from " + url, e);
            }
        }

        log.info("[FakerSuggestion] All model files downloaded successfully");
    }
}
