package de.mhus.vance.brain.tools.tex;

import de.mhus.vance.shared.settings.SettingService;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Remote {@link Tex2PdfExecutor} that delegates compilation to the
 * <a href="https://hub.docker.com/r/rbehzadan/tex2pdf">rbehzadan/tex2pdf</a>
 * REST service.
 *
 * <p>Flow:
 * <ol>
 *   <li>Zip the entire workspace root directory</li>
 *   <li>{@code POST /tex2pdf} with the ZIP as multipart form data → {@code job_id}</li>
 *   <li>Poll {@code GET /tex2pdf/status/{job_id}} until completed/failed</li>
 *   <li>{@code GET /tex2pdf/download/{job_id}} → PDF bytes</li>
 * </ol>
 *
 * <p>Always registered as a Spring bean; selected at runtime when the
 * {@code tex.executor} setting resolves to {@code "rbehzadan"}.
 *
 * <p>Configuration is read per-call from the settings cascade
 * ({@code think-process → project → _tenant}), with application
 * properties as fallback:
 * <ul>
 *   <li>{@code tex.rbehzadan.url} (STRING) — base URL of the service
 *       (required; fallback: {@code vance.tex.rbehzadan.url})</li>
 *   <li>{@code tex.rbehzadan.apiKey} (PASSWORD) — API key for the
 *       X-API-Key header (optional; fallback:
 *       {@code vance.tex.rbehzadan.api-key})</li>
 *   <li>{@code tex.rbehzadan.timeoutSeconds} (INT) — overall timeout
 *       (default 300; fallback: {@code vance.tex.rbehzadan.timeout-seconds})</li>
 *   <li>{@code tex.rbehzadan.pollIntervalSeconds} (INT) — poll interval
 *       (default 3; fallback: {@code vance.tex.rbehzadan.poll-interval-seconds})</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class Tex2PdfRbehzadanExecutor implements Tex2PdfExecutor {

    static final String SETTING_URL = "tex.rbehzadan.url";
    static final String SETTING_API_KEY = "tex.rbehzadan.apiKey";
    static final String SETTING_TIMEOUT = "tex.rbehzadan.timeoutSeconds";
    static final String SETTING_POLL_INTERVAL = "tex.rbehzadan.pollIntervalSeconds";

    private final ObjectMapper objectMapper;
    private final SettingService settings;

    @Value("${vance.tex.rbehzadan.url:}")
    private String defaultUrl;
    @Value("${vance.tex.rbehzadan.api-key:}")
    private String defaultApiKey;
    @Value("${vance.tex.rbehzadan.timeout-seconds:300}")
    private long defaultTimeoutSeconds;
    @Value("${vance.tex.rbehzadan.poll-interval-seconds:3}")
    private long defaultPollIntervalSeconds;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .build();

    @Override
    public String type() {
        return "rbehzadan";
    }

    @Override
    public Result compile(Request request) {
        long start = System.currentTimeMillis();

        // Resolve settings per-call from the cascade
        String baseUrl = resolveUrl(request);
        if (baseUrl == null || baseUrl.isBlank()) {
            return Result.failure(
                    "tex.rbehzadan.url is not configured — set it in Settings "
                            + "(key: '" + SETTING_URL + "') or application property "
                            + "'vance.tex.rbehzadan.url'",
                    null, System.currentTimeMillis() - start);
        }
        baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        String apiKey = resolveApiKey(request);
        long timeoutSeconds = resolveLong(request, SETTING_TIMEOUT, defaultTimeoutSeconds, 300);
        long pollIntervalSeconds = resolveLong(request, SETTING_POLL_INTERVAL, defaultPollIntervalSeconds, 3);
        long deadline = start + (timeoutSeconds * 1000);

        try {
            // 1. Zip the workspace root
            byte[] zipBytes = zipDirectory(request.workspaceRoot());
            log.debug("tex2pdf: zipped {} bytes from {}", zipBytes.length, request.workspaceRoot());

            // 2. Submit the job
            String jobId = submitJob(baseUrl, apiKey, zipBytes);
            log.debug("tex2pdf: submitted job {}", jobId);

            // 3. Poll until done
            while (true) {
                if (System.currentTimeMillis() > deadline) {
                    return Result.failure(
                            "TIMEOUT: tex2pdf job " + jobId + " did not finish within "
                                    + timeoutSeconds + "s",
                            null, System.currentTimeMillis() - start);
                }

                Thread.sleep(pollIntervalSeconds * 1000);

                JsonNode status = checkStatus(baseUrl, apiKey, jobId);
                String statusStr = status.path("status").asText("");
                log.debug("tex2pdf: job {} status: {}", jobId, statusStr);

                if ("completed".equalsIgnoreCase(statusStr)
                        || "done".equalsIgnoreCase(statusStr)) {
                    // 4. Download the PDF
                    byte[] pdf = downloadResult(baseUrl, apiKey, jobId);
                    String logText = extractLog(status);
                    return Result.success(pdf, logText, System.currentTimeMillis() - start);
                }
                if ("failed".equalsIgnoreCase(statusStr)
                        || "error".equalsIgnoreCase(statusStr)) {
                    String errorMsg = status.path("error").asText(
                            "tex2pdf service reported compilation failure");
                    String logText = extractLog(status);
                    return Result.failure(errorMsg, logText, System.currentTimeMillis() - start);
                }
                // still processing/queued — continue polling
            }

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Result.failure("Interrupted", null, System.currentTimeMillis() - start);
        } catch (IOException e) {
            log.warn("tex2pdf: rbehzadan executor error: {}", e.toString());
            return Result.failure(
                    "tex2pdf service error: " + e.getMessage(),
                    null, System.currentTimeMillis() - start);
        }
    }

    // ──────────────────── settings resolution ────────────────────

    private @Nullable String resolveUrl(Request request) {
        String v = settings.getStringValueCascade(
                request.tenantId(), request.projectId(), request.processId(), SETTING_URL);
        if (v != null && !v.isBlank()) return v.trim();
        return defaultUrl != null && !defaultUrl.isBlank() ? defaultUrl.trim() : null;
    }

    private @Nullable String resolveApiKey(Request request) {
        String v = settings.getDecryptedPasswordCascade(
                request.tenantId(), request.projectId(), request.processId(), SETTING_API_KEY);
        if (v != null && !v.isBlank()) return v.trim();
        return defaultApiKey != null && !defaultApiKey.isBlank() ? defaultApiKey.trim() : null;
    }

    private long resolveLong(Request request, String key, long appDefault, long hardDefault) {
        long v = settings.getIntValue(
                request.tenantId(),
                SettingService.SCOPE_PROJECT,
                request.projectId() != null ? request.projectId() : "_tenant",
                key, (int) appDefault);
        // Also try the cascade — getIntValue doesn't have a cascade variant
        String cascadeVal = settings.getStringValueCascade(
                request.tenantId(), request.projectId(), request.processId(), key);
        if (cascadeVal != null && !cascadeVal.isBlank()) {
            try {
                return Long.parseLong(cascadeVal.trim());
            } catch (NumberFormatException ignored) {
                // fall through to appDefault
            }
        }
        return v > 0 ? v : hardDefault;
    }

    // ──────────────────── ZIP ────────────────────

    private byte[] zipDirectory(Path rootDir) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(baos)) {
            Files.walk(rootDir)
                    .filter(Files::isRegularFile)
                    .forEach(file -> {
                        String relativePath = rootDir.relativize(file)
                                .toString()
                                .replace('\\', '/');
                        ZipEntry entry = new ZipEntry(relativePath);
                        try {
                            zos.putNextEntry(entry);
                            Files.copy(file, zos);
                            zos.closeEntry();
                        } catch (IOException e) {
                            throw new RuntimeException("Failed to zip " + file, e);
                        }
                    });
        }
        return baos.toByteArray();
    }

    // ──────────────────── API calls ────────────────────

    private String submitJob(String baseUrl, @Nullable String apiKey, byte[] zipBytes)
            throws IOException, InterruptedException {
        String boundary = "vance-boundary-" + System.currentTimeMillis();

        ByteArrayOutputStream body = new ByteArrayOutputStream();
        // Multipart form: zip_file field
        writeMultipartField(body, boundary, "zip_file", "project.zip", "application/zip", zipBytes);
        body.write(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));

        HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/tex2pdf"))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray()));

        if (apiKey != null) {
            reqBuilder.header("X-API-Key", apiKey);
        }

        HttpResponse<String> response = httpClient.send(
                reqBuilder.build(), HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200 && response.statusCode() != 201) {
            throw new IOException("tex2pdf submit failed (HTTP " + response.statusCode()
                    + "): " + response.body());
        }

        JsonNode json = objectMapper.readTree(response.body());
        String jobId = json.path("job_id").asText("");
        if (jobId.isEmpty()) {
            throw new IOException("tex2pdf submit response missing job_id: " + response.body());
        }
        return jobId;
    }

    private JsonNode checkStatus(String baseUrl, @Nullable String apiKey, String jobId)
            throws IOException, InterruptedException {
        HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/tex2pdf/status/" + jobId))
                .GET();

        if (apiKey != null) {
            reqBuilder.header("X-API-Key", apiKey);
        }

        HttpResponse<String> response = httpClient.send(
                reqBuilder.build(), HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            throw new IOException("tex2pdf status check failed (HTTP "
                    + response.statusCode() + "): " + response.body());
        }

        return objectMapper.readTree(response.body());
    }

    private byte[] downloadResult(String baseUrl, @Nullable String apiKey, String jobId)
            throws IOException, InterruptedException {
        HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/tex2pdf/download/" + jobId))
                .GET();

        if (apiKey != null) {
            reqBuilder.header("X-API-Key", apiKey);
        }

        HttpResponse<byte[]> response = httpClient.send(
                reqBuilder.build(), HttpResponse.BodyHandlers.ofByteArray());

        if (response.statusCode() != 200) {
            throw new IOException("tex2pdf download failed (HTTP "
                    + response.statusCode() + ")");
        }

        return response.body();
    }

    // ──────────────────── helpers ────────────────────

    private @Nullable String extractLog(JsonNode status) {
        JsonNode logNode = status.path("log");
        if (logNode.isMissingNode() || logNode.isNull()) {
            return null;
        }
        return logNode.asText();
    }

    private void writeMultipartField(ByteArrayOutputStream out, String boundary,
                                     String fieldName, String filename,
                                     String contentType, byte[] data) throws IOException {
        out.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
        out.write(("Content-Disposition: form-data; name=\"" + fieldName
                + "\"; filename=\"" + filename + "\"\r\n").getBytes(StandardCharsets.UTF_8));
        out.write(("Content-Type: " + contentType + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        out.write(data);
        out.write("\r\n".getBytes(StandardCharsets.UTF_8));
    }
}
