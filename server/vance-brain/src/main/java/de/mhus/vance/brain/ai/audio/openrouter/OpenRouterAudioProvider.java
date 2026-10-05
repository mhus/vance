package de.mhus.vance.brain.ai.audio.openrouter;

import de.mhus.vance.brain.ai.ProviderType;
import de.mhus.vance.brain.ai.audio.AiAudioConfig;
import de.mhus.vance.brain.ai.audio.AiAudioException;
import de.mhus.vance.brain.ai.audio.AiAudioModelProvider;
import de.mhus.vance.brain.ai.audio.AudioMimeTypeSniffer;
import de.mhus.vance.brain.ai.audio.AudioSource;
import de.mhus.vance.brain.ai.audio.PcmWav;
import de.mhus.vance.brain.ai.audio.SttResult;
import de.mhus.vance.brain.ai.audio.TtsRequest;
import de.mhus.vance.shared.document.AudioDestinationStream;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Dedicated audio provider for the {@code openrouter} named instance —
 * OpenRouter's audio surface is its own set of endpoints, not the
 * OpenAI wire the instance declares for chat. Dispatch hangs off
 * {@link #getInstanceName()} ({@code "openrouter"}).
 *
 * <p>Three operations, three wire shapes (all verified live 2026-10-05):
 *
 * <ul>
 *   <li><b>TTS</b> — {@code POST /api/v1/audio/speech}
 *       ({@code model, input, voice, response_format, speed?}) → raw
 *       audio byte stream. Some models only serve {@code pcm}
 *       (Gemini TTS rejects anything else), so a format rejection is
 *       retried once as {@code pcm} and wrapped into a WAV container.
 *       The response {@code Content-Type} announces rate/channels
 *       ({@code audio/pcm;rate=24000;channels=1}); the bytes are the
 *       authority for the committed mime ({@link AudioMimeTypeSniffer}).</li>
 *   <li><b>STT</b> — {@code POST /api/v1/audio/transcriptions}
 *       ({@code model, input_audio:{data,format}, language?}) →
 *       {@code {text, usage:{seconds, cost}}}. The vendor-reported
 *       {@code usage.cost} is passed back to the caller.</li>
 *   <li><b>Music</b> — {@code POST /api/v1/chat/completions} with
 *       {@code modalities:["text","audio"]} and {@code stream:true}
 *       (the endpoint rejects non-streaming audio output with a 400).
 *       Audio arrives as base64 chunks in {@code choices[].delta.audio
 *       .data}; {@code usage.cost} rides the closing chunk. Lyria
 *       answers a {@code format:"wav"} request with MP3 bytes, so the
 *       committed mime is sniffed, never assumed.</li>
 * </ul>
 *
 * <p>Failures arrive as {@code {error:{message}}} with an HTTP status;
 * the message is preserved verbatim so the service's error classifier
 * can map timeouts / policy rejections.
 */
@Component
public class OpenRouterAudioProvider implements AiAudioModelProvider {

    /** Dispatch key — matches the bundled sidecar's instance name. */
    static final String INSTANCE_NAME = "openrouter";

    private static final String DEFAULT_BASE_URL = "https://openrouter.ai/api/v1";
    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final Pattern PCM_PARAMS =
            Pattern.compile("rate=(\\d+).*?channels=(\\d+)|channels=(\\d+).*?rate=(\\d+)");

    private final String defaultBaseUrl;
    private final HttpClient httpClient;

    public OpenRouterAudioProvider(@Value("${vance.ai.openrouter.base-url:}") String baseUrl) {
        this.defaultBaseUrl = baseUrl == null || baseUrl.isBlank() ? DEFAULT_BASE_URL : baseUrl.trim();
        this.httpClient = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    @Override
    public ProviderType getType() {
        // The instance is declared with wireType: openai for chat; this
        // adapter is selected via getInstanceName(), never via the
        // protocol type (that would steal OpenAI-proper calls).
        return ProviderType.OPENAI;
    }

    @Override
    public Optional<String> getInstanceName() {
        return Optional.of(INSTANCE_NAME);
    }

    // ──────────────────── TTS ────────────────────

    @Override
    public void synthesize(AiAudioConfig config, TtsRequest request, AudioDestinationStream destination) {
        String wireFormat = "wav".equals(request.format()) ? "pcm" : request.format();
        HttpResponse<byte[]> response = execute(config, "/audio/speech", ttsBody(config, request, wireFormat));
        byte[] audio = response.body();

        // Some TTS models serve pcm only (Gemini TTS rejects mp3 with a 400
        // that names response_format) — one retry as pcm, wrapped into a WAV
        // container so the document stays playable.
        if (isFormatRejection(response)) {
            response = execute(config, "/audio/speech", ttsBody(config, request, "pcm"));
            audio = wrapPcm(response);
            wireFormat = "pcm";
        } else if (response.statusCode() != 200) {
            throw new AiAudioException("OpenRouter /audio/speech failed (HTTP " + response.statusCode() + "): "
                    + errorMessage(response.body()));
        } else if ("pcm".equals(wireFormat)) {
            audio = wrapPcm(response);
        }

        String mime = AudioMimeTypeSniffer.sniff(audio, "wav".equals(wireFormat) ? "audio/wav" : "audio/mpeg");
        destination.setMimeType(mime);
        destination.setMetadata("model", config.fullName());
        if (request.voice() != null) {
            destination.setMetadata("voice", request.voice());
        }
        if (request.language() != null) {
            destination.setMetadata("language", request.language());
        }
        destination.setMetadata("textChars", Integer.toString(request.text().length()));
        String generationId = response.headers().firstValue("x-generation-id").orElse(null);
        if (generationId != null) {
            destination.setMetadata("generationId", generationId);
        }
        destination.write(audio, 0, audio.length);
        destination.close();
    }

    private static ObjectNode ttsBody(AiAudioConfig config, TtsRequest request, String wireFormat) {
        ObjectNode body = MAPPER.createObjectNode();
        body.put("model", config.modelName());
        body.put("input", request.text());
        if (request.voice() != null && !request.voice().isBlank()) {
            body.put("voice", request.voice());
        }
        body.put("response_format", wireFormat);
        if (request.speed() != null && request.speed() > 0) {
            body.put("speed", request.speed());
        }
        return body;
    }

    /** Wrap raw pcm bytes (with the response's rate/channels) into WAV. */
    private static byte[] wrapPcm(HttpResponse<byte[]> response) {
        int rate = PcmWav.DEFAULT_SAMPLE_RATE;
        int channels = 1;
        String contentType = response.headers().firstValue("content-type").orElse("");
        Matcher m = PCM_PARAMS.matcher(contentType);
        if (m.find()) {
            if (m.group(1) != null) {
                rate = Integer.parseInt(m.group(1));
                channels = Integer.parseInt(m.group(2));
            } else {
                channels = Integer.parseInt(m.group(3));
                rate = Integer.parseInt(m.group(4));
            }
        }
        return PcmWav.wrap(response.body(), rate, channels);
    }

    private static boolean isFormatRejection(HttpResponse<byte[]> response) {
        if (response.statusCode() != 400) {
            return false;
        }
        String message = errorMessage(response.body());
        return message != null && message.toLowerCase(java.util.Locale.ROOT).contains("response_format");
    }

    // ──────────────────── STT ────────────────────
    @Override
    public SttResult transcribe(AiAudioConfig config, AudioSource source, @Nullable String language) {
        ObjectNode body = MAPPER.createObjectNode();
        body.put("model", config.modelName());
        ObjectNode inputAudio = body.putObject("input_audio");
        inputAudio.put("data", Base64.getEncoder().encodeToString(source.data()));
        inputAudio.put("format", source.format());
        if (language != null && !language.isBlank()) {
            body.put("language", language.trim());
        }
        HttpResponse<byte[]> response = execute(config, "/audio/transcriptions", body);
        if (response.statusCode() != 200) {
            throw new AiAudioException("OpenRouter /audio/transcriptions failed (HTTP " + response.statusCode() + "): "
                    + errorMessage(response.body()));
        }
        JsonNode root = MAPPER.readTree(response.body());
        String text = root.path("text").asString("");
        JsonNode usage = root.path("usage");
        Double seconds = usage.has("seconds") ? usage.path("seconds").asDouble() : null;
        Double cost = usage.has("cost") ? usage.path("cost").asDouble() : null;
        Double duration = usage.has("duration") ? usage.path("duration").asDouble() : seconds;
        return new SttResult(text, language, duration, cost);
    }

    // ──────────────────── Music ────────────────────

    @Override
    public void generateMusic(
            AiAudioConfig config, String prompt, int durationSeconds, AudioDestinationStream destination) {
        ObjectNode body = MAPPER.createObjectNode();
        body.put("model", config.modelName());
        body.putArray("messages").addObject().put("role", "user").put("content", prompt);
        body.putArray("modalities").add("text").add("audio");
        body.putObject("audio").put("format", "wav");
        body.put("stream", true);

        String baseUrl = baseUrl(config);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/chat/completions"))
                .timeout(Duration.ofSeconds(config.timeoutSeconds()))
                .header("Authorization", "Bearer " + requireKey(config))
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(body), StandardCharsets.UTF_8))
                .build();

        ByteArrayOutputStream audio = new ByteArrayOutputStream();
        Double[] reportedCost = new Double[1];
        StringBuilder transcript = new StringBuilder();
        try {
            HttpResponse<java.io.InputStream> response =
                    httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() != 200) {
                byte[] err = response.body().readAllBytes();
                throw new AiAudioException("OpenRouter music generation failed (HTTP " + response.statusCode() + "): "
                        + errorMessage(err));
            }
            readSse(response.body(), audio, reportedCost, transcript);
        } catch (AiAudioException e) {
            throw e;
        } catch (java.io.IOException e) {
            throw new AiAudioException(
                    "OpenRouter music generation failed for " + config.fullName() + ": " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AiAudioException("OpenRouter music generation interrupted for " + config.fullName(), e);
        }

        byte[] bytes = audio.toByteArray();
        if (bytes.length == 0) {
            throw new AiAudioException("OpenRouter music generation returned no audio for " + config.fullName());
        }
        destination.setMimeType(AudioMimeTypeSniffer.sniff(bytes, "audio/mpeg"));
        destination.setMetadata("model", config.fullName());
        destination.setMetadata("promptChars", Integer.toString(prompt.length()));
        if (durationSeconds > 0) {
            destination.setMetadata("requestedDurationSeconds", Integer.toString(durationSeconds));
        }
        if (reportedCost[0] != null) {
            destination.setMetadata("costUsd", Double.toString(reportedCost[0]));
        }
        if (!transcript.isEmpty()) {
            destination.setMetadata("transcript", truncate(transcript.toString(), 500));
        }
        destination.write(bytes, 0, bytes.length);
        destination.close();
    }

    /** Parse the SSE stream: base64 audio chunks + the closing usage. */
    private static void readSse(
            java.io.InputStream in, ByteArrayOutputStream audio, Double[] reportedCost, StringBuilder transcript)
            throws java.io.IOException {
        try (var reader = new java.io.BufferedReader(new java.io.InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (!line.startsWith("data:")) {
                    continue;
                }
                String payload = line.substring("data:".length()).trim();
                if (payload.isEmpty() || "[DONE]".equals(payload)) {
                    continue;
                }
                JsonNode chunk = MAPPER.readTree(payload.getBytes(StandardCharsets.UTF_8));
                if (chunk.has("usage") && chunk.path("usage").has("cost")) {
                    reportedCost[0] = chunk.path("usage").path("cost").asDouble();
                }
                for (JsonNode choice : chunk.path("choices")) {
                    JsonNode delta = choice.path("delta");
                    String b64 = delta.path("audio").path("data").asString(null);
                    if (b64 != null && !b64.isEmpty()) {
                        byte[] part = Base64.getDecoder().decode(b64);
                        audio.write(part, 0, part.length);
                    }
                    String content = delta.path("content").asString(null);
                    if (content != null) {
                        transcript.append(content);
                    }
                }
            }
        }
    }

    // ──────────────────── shared HTTP ────────────────────

    /** POST one JSON body; the caller inspects the status. */
    private HttpResponse<byte[]> execute(AiAudioConfig config, String route, ObjectNode body) {
        String baseUrl = baseUrl(config);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + route))
                .timeout(Duration.ofSeconds(config.timeoutSeconds()))
                .header("Authorization", "Bearer " + requireKey(config))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(body), StandardCharsets.UTF_8))
                .build();
        try {
            return httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
        } catch (java.io.IOException e) {
            throw new AiAudioException(
                    "OpenRouter " + route + " failed for " + config.fullName() + ": " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AiAudioException("OpenRouter " + route + " interrupted for " + config.fullName(), e);
        }
    }

    private String baseUrl(AiAudioConfig config) {
        String base = config.baseUrl() != null ? config.baseUrl() : defaultBaseUrl;
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return base;
    }

    private static String requireKey(AiAudioConfig config) {
        if (config.apiKey() == null || config.apiKey().isBlank()) {
            throw new AiAudioException("No API key configured for " + config.fullName() + " — set ai.provider."
                    + config.providerInstance() + ".apiKey");
        }
        return config.apiKey();
    }

    /** Best-effort {@code {error:{message}}} extraction; falls back to the raw body. */
    private static String errorMessage(byte[] body) {
        if (body == null || body.length == 0) {
            return "(no error body)";
        }
        try {
            JsonNode root = MAPPER.readTree(body);
            String message = root.path("error").path("message").asString(null);
            if (message != null) {
                return message;
            }
        } catch (RuntimeException ignored) {
            // not JSON — fall through to the raw text
        }
        String raw = new String(body, StandardCharsets.UTF_8);
        return truncate(raw, 300);
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
