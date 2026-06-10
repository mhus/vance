package de.mhus.vance.brain.fenchurch;

import de.mhus.vance.api.progress.StatusTag;
import de.mhus.vance.brain.ai.AiModelResolver;
import de.mhus.vance.brain.ai.ChatBehaviorBuilder;
import de.mhus.vance.brain.ai.ModelCatalog;
import de.mhus.vance.brain.ai.image.AiImageConfig;
import de.mhus.vance.brain.ai.image.AiImageException;
import de.mhus.vance.brain.ai.image.AiImageService;
import de.mhus.vance.brain.ai.image.DocumentImageDestinationStream;
import de.mhus.vance.brain.ai.image.ImageModelInfo;
import de.mhus.vance.brain.ai.light.LightLlmException;
import de.mhus.vance.brain.ai.light.LightLlmRequest;
import de.mhus.vance.brain.ai.light.LightLlmService;
import de.mhus.vance.brain.progress.ProgressEmitter;
import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.shared.settings.SettingService;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Entry point of the Fenchurch image-generation stack — the
 * synchronous, single-shot service that runs from the
 * {@code image_generate} tool through the {@link AiImageService}
 * provider dispatch into a {@link DocumentImageDestinationStream}
 * that commits the bytes to the document store.
 *
 * <p>Per-call sequence (also documented in
 * {@code planning/fenchurch-service.md} §3):
 *
 * <ol>
 *   <li>Quota gate via {@link ImageCallTracker#checkQuota} — reject
 *       early when limits are hit.</li>
 *   <li>Style cascade merge via {@link FenchurchStyleService}.</li>
 *   <li>Optional title + slug generation via the {@code image-title}
 *       LightLlm recipe (skipped when the caller passes both
 *       {@code title} and {@code path}, or when
 *       {@code ai.fenchurch.auto_title_from_prompt = false}).</li>
 *   <li>Alias → {@code (provider, model)} resolution +
 *       {@link AiImageConfig} assembly.</li>
 *   <li>{@link ProgressEmitter} status-open + heartbeat ticker
 *       (skipped when no {@code processId} is supplied).</li>
 *   <li>{@link AiImageService#generate} into a fresh
 *       {@link DocumentImageDestinationStream} — provider commits
 *       bytes + metadata + title in one
 *       {@link DocumentService#createOrReplaceBinary} call.</li>
 *   <li>{@link ImageCallTracker#recordCall} — one persisted row,
 *       success or failure.</li>
 * </ol>
 *
 * <p>Concurrency: synchronous. The caller's lane lock is the only
 * serialisation; no per-pod semaphore in v1 (planned for v1.1, see
 * the planning doc §7).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class FenchurchService {

    public static final String DEFAULT_IMAGE_ALIAS = "default:image";
    public static final String SETTING_ENABLED = "ai.fenchurch.enabled";
    public static final String SETTING_TIMEOUT = "ai.fenchurch.timeout";
    public static final String SETTING_DEFAULT_ASPECT = "ai.fenchurch.default_aspect_ratio";
    public static final String SETTING_HEARTBEAT = "ai.fenchurch.heartbeat_interval_sec";
    public static final String SETTING_AUTO_TITLE = "ai.fenchurch.auto_title_from_prompt";

    public static final int DEFAULT_TIMEOUT_SECONDS = 360;
    public static final int DEFAULT_HEARTBEAT_INTERVAL_SECONDS = 30;
    public static final String DEFAULT_ASPECT_RATIO = "1:1";
    public static final String IMAGE_TITLE_RECIPE = "image-title";

    private final FenchurchStyleService styleService;
    private final ImageCallTracker callTracker;
    private final AiModelResolver modelResolver;
    private final ModelCatalog modelCatalog;
    private final AiImageService imageService;
    private final LightLlmService lightLlm;
    private final SettingService settingService;
    private final DocumentService documentService;
    private final ProgressEmitter progressEmitter;
    private final ThinkProcessService thinkProcessService;

    @Value("${vance.fenchurch.scheduler-pool-size:2}")
    private int schedulerPoolSize;

    private ScheduledExecutorService scheduler;

    @jakarta.annotation.PostConstruct
    void init() {
        int size = Math.max(1, schedulerPoolSize);
        AtomicInteger seq = new AtomicInteger();
        this.scheduler = Executors.newScheduledThreadPool(size, r -> {
            Thread t = new Thread(r, "fenchurch-heartbeat-" + seq.incrementAndGet());
            t.setDaemon(true);
            return t;
        });
    }

    @jakarta.annotation.PreDestroy
    void shutdown() {
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
    }

    /**
     * Generate one image. Synchronous: returns when the document is
     * committed or throws {@link FenchurchException} on any failure
     * mapped to a stable {@link FenchurchException.Reason}.
     */
    public GenerateImageResult generate(GenerateImageRequest request) {
        validate(request);
        ensureEnabled(request);
        ImageCallTracker.Verdict quota = callTracker.checkQuota(
                request.getTenantId(), request.getUserId(),
                request.getProjectId(), request.getProcessId());
        if (!quota.allowed()) {
            recordRejected(request, "quota_exceeded", null, null, 0L);
            throw new FenchurchException(
                    FenchurchException.Reason.QUOTA_EXCEEDED,
                    quota.message() == null ? "Quota exceeded" : quota.message());
        }

        long callStart = System.currentTimeMillis();
        String resolvedAlias = request.getAlias() == null || request.getAlias().isBlank()
                ? DEFAULT_IMAGE_ALIAS : request.getAlias();
        AiModelResolver.Resolved resolved = modelResolver.resolveOrDefault(
                resolvedAlias, request.getTenantId(),
                request.getProjectId(), request.getProcessId());

        ImageModelInfo modelInfo = modelCatalog.lookupImage(
                request.getTenantId(), request.getProjectId(),
                resolved.providerInstance(), resolved.modelName())
                .orElseGet(() -> modelCatalog.lookupImage(
                        request.getTenantId(), request.getProjectId(),
                        resolved.provider(), resolved.modelName())
                        .orElseThrow(() -> new FenchurchException(
                                FenchurchException.Reason.PROVIDER_ERROR,
                                "No image model entry for "
                                        + resolved.provider() + ":" + resolved.modelName()
                                        + " — add it to ai-models.yaml with kind: image")));

        String aspectRatio = resolveAspectRatio(request, modelInfo);
        String prompt = composePrompt(request);
        ensurePromptFits(prompt, modelInfo);

        TitleResolution titleResolution = resolveTitleAndSlug(request, prompt);
        String path = resolvePath(request, titleResolution);

        int timeoutSeconds = resolveTimeout(request, modelInfo);
        AiImageConfig config = buildImageConfig(
                request, resolved, aspectRatio, timeoutSeconds);

        ThinkProcessDocument process = loadProcess(request);
        ScheduledFuture<?> heartbeat = startHeartbeat(process, resolvedAlias, callStart);

        try {
            DocumentImageDestinationStream stream = new DocumentImageDestinationStream(
                    documentService,
                    request.getTenantId(),
                    resolveProjectId(request),
                    path,
                    request.getUserId());
            // Push the caller-supplied or LightLlm-generated title onto
            // the stream BEFORE the provider call so the eventual
            // DocumentService.createOrReplaceBinary (commit on stream
            // close) writes it through to DocumentDocument.title.
            // Providers do not see / touch the title — the stream is the
            // only owner.
            if (titleResolution.title() != null) {
                stream.setTitle(titleResolution.title());
            }
            imageService.generate(config, prompt, stream);
        } catch (AiImageException e) {
            cancelHeartbeat(heartbeat);
            recordFailure(request, resolved, config, callStart, e);
            throw mapProviderError(e, config);
        } catch (RuntimeException e) {
            cancelHeartbeat(heartbeat);
            recordFailure(request, resolved, config, callStart, e);
            throw new FenchurchException(
                    FenchurchException.Reason.PROVIDER_ERROR,
                    "Fenchurch generation failed for " + config.fullName()
                            + ": " + e.getMessage(), e);
        }
        cancelHeartbeat(heartbeat);

        long durationMs = System.currentTimeMillis() - callStart;
        DocumentDocument committed = documentService.findByPath(
                request.getTenantId(), resolveProjectId(request), path)
                .orElseThrow(() -> new FenchurchException(
                        FenchurchException.Reason.PROVIDER_ERROR,
                        "Image generation reported success but the document at "
                                + path + " was not committed"));

        recordSuccess(request, resolved, config, modelInfo,
                callStart, durationMs, committed);

        return GenerateImageResult.builder()
                .path(committed.getPath())
                .mimeType(committed.getMimeType() == null
                        ? "image/png" : committed.getMimeType())
                .sizeBytes(committed.getSize())
                .modelUsed(config.fullName())
                .durationMs(durationMs)
                .title(committed.getTitle())
                .build();
    }

    // ──────────────────── Validation / gating ────────────────────

    private static void validate(GenerateImageRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("request is required");
        }
        if (request.getTenantId() == null || request.getTenantId().isBlank()) {
            throw new IllegalArgumentException("tenantId is required");
        }
        if (request.getPrompt() == null || request.getPrompt().isBlank()) {
            throw new IllegalArgumentException("prompt is required");
        }
    }

    private void ensureEnabled(GenerateImageRequest request) {
        boolean enabled = settingService.getBooleanValueCascade(
                request.getTenantId(), request.getProjectId(),
                request.getProcessId(), SETTING_ENABLED, true);
        if (!enabled) {
            throw new FenchurchException(
                    FenchurchException.Reason.DISABLED,
                    "Image generation is disabled in this scope "
                            + "(ai.fenchurch.enabled = false)");
        }
    }

    private static void ensurePromptFits(String prompt, ImageModelInfo modelInfo) {
        if (prompt.length() > modelInfo.maxPromptChars()) {
            throw new FenchurchException(
                    FenchurchException.Reason.PROMPT_TOO_LONG,
                    "Effective prompt is " + prompt.length() + " chars; model "
                            + modelInfo.provider() + ":" + modelInfo.modelName()
                            + " caps at " + modelInfo.maxPromptChars()
                            + ". Shorten the prompt or trim the style prefix.");
        }
    }

    // ──────────────────── Prompt + style composition ────────────────────

    /** Merges style-cascade + worker prompt. Style layer is prepended
     *  with a blank line separator when present. */
    private String composePrompt(GenerateImageRequest request) {
        String style = styleService.mergedPrompt(
                request.getTenantId(), request.getUserId(),
                request.getProjectId(), request.getProcessId());
        String body = request.getPrompt().trim();
        if (style == null || style.isBlank()) {
            return body;
        }
        return style.trim() + "\n\n" + body;
    }

    private String resolveAspectRatio(
            GenerateImageRequest request, ImageModelInfo modelInfo) {
        String aspect = request.getAspectRatio();
        if (aspect == null || aspect.isBlank()) {
            aspect = settingService.getStringValueCascade(
                    request.getTenantId(), request.getProjectId(),
                    request.getProcessId(), SETTING_DEFAULT_ASPECT);
        }
        if (aspect == null || aspect.isBlank()) {
            aspect = DEFAULT_ASPECT_RATIO;
        }
        if (!modelInfo.supportsAspectRatio(aspect)) {
            throw new FenchurchException(
                    FenchurchException.Reason.UNSUPPORTED_ASPECT_RATIO,
                    "Aspect ratio " + aspect + " is not supported by "
                            + modelInfo.provider() + ":" + modelInfo.modelName()
                            + " — try one of " + modelInfo.supportedAspectRatios());
        }
        return aspect;
    }

    // ──────────────────── Title + path resolution ────────────────────

    /** Title + slug pair as resolved per-call. {@code slug} is always
     *  ASCII kebab-case suitable for inclusion in a path. */
    private record TitleResolution(@Nullable String title, String slug) {}

    private TitleResolution resolveTitleAndSlug(
            GenerateImageRequest request, String composedPrompt) {
        String explicit = request.getTitle();
        if (explicit != null && !explicit.isBlank()) {
            return new TitleResolution(explicit, slugify(explicit));
        }
        boolean autoTitle = settingService.getBooleanValueCascade(
                request.getTenantId(), request.getProjectId(),
                request.getProcessId(), SETTING_AUTO_TITLE, true);
        if (!autoTitle) {
            return new TitleResolution(null, "image");
        }
        try {
            Map<String, Object> reply = lightLlm.callForJson(LightLlmRequest.builder()
                    .recipeName(IMAGE_TITLE_RECIPE)
                    .userPrompt(composedPrompt)
                    .pebbleVars(Map.of("prompt", composedPrompt))
                    .tenantId(request.getTenantId())
                    .projectId(request.getProjectId())
                    .processId(request.getProcessId())
                    .build());
            String title = stringOrNull(reply.get("title"));
            String slug = stringOrNull(reply.get("slug"));
            if (slug == null || slug.isBlank()) {
                slug = slugify(title == null ? "image" : title);
            }
            slug = sanitizeSlug(slug);
            return new TitleResolution(title, slug);
        } catch (LightLlmException e) {
            log.info("FenchurchService: title generation failed ({}), "
                            + "falling back to 'image' slug",
                    e.getMessage());
            return new TitleResolution(null, "image");
        }
    }

    /** Caller-supplied path wins; otherwise build
     *  {@code images/<uuid8>-<slug>.png}. */
    private static String resolvePath(
            GenerateImageRequest request, TitleResolution title) {
        if (request.getPath() != null && !request.getPath().isBlank()) {
            return request.getPath().trim();
        }
        String uuid8 = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        return "images/" + uuid8 + "-" + title.slug() + ".png";
    }

    /** Lower-case kebab-case ASCII. Strips diacritics, drops everything
     *  that isn't a-z 0-9, collapses runs of hyphens, trims to 30
     *  characters. */
    static String slugify(String input) {
        if (input == null) return "image";
        String normalized = java.text.Normalizer.normalize(input, java.text.Normalizer.Form.NFD);
        StringBuilder out = new StringBuilder(input.length());
        boolean lastHyphen = true;
        for (char c : normalized.toCharArray()) {
            // Diacritic combining marks (Mn) are decomposed leftovers
            // from NFD ("ä" → "a" + combining diaeresis) — silently
            // drop them rather than letting them collapse to hyphens
            // and split words.
            if (Character.getType(c) == Character.NON_SPACING_MARK) {
                continue;
            }
            char lower = Character.toLowerCase(c);
            if ((lower >= 'a' && lower <= 'z') || (lower >= '0' && lower <= '9')) {
                out.append(lower);
                lastHyphen = false;
            } else if (!lastHyphen) {
                out.append('-');
                lastHyphen = true;
            }
            if (out.length() >= 30) break;
        }
        // Trim trailing hyphen and clamp to ≥1 char.
        while (out.length() > 0 && out.charAt(out.length() - 1) == '-') {
            out.deleteCharAt(out.length() - 1);
        }
        return out.length() == 0 ? "image" : out.toString();
    }

    static String sanitizeSlug(String input) {
        return slugify(input);
    }

    // ──────────────────── Config + timeout ────────────────────

    private int resolveTimeout(GenerateImageRequest request, ImageModelInfo modelInfo) {
        String override = settingService.getStringValueCascade(
                request.getTenantId(), request.getProjectId(),
                request.getProcessId(), SETTING_TIMEOUT);
        if (override != null && !override.isBlank()) {
            try {
                int parsed = Integer.parseInt(override.trim());
                if (parsed > 0) return parsed;
            } catch (NumberFormatException ignored) {
                log.warn("FenchurchService: non-numeric '{}' setting '{}' — using model default",
                        SETTING_TIMEOUT, override);
            }
        }
        int modelDefault = modelInfo.timeoutSeconds();
        return modelDefault > 0 ? modelDefault : DEFAULT_TIMEOUT_SECONDS;
    }

    private AiImageConfig buildImageConfig(
            GenerateImageRequest request,
            AiModelResolver.Resolved resolved,
            String aspectRatio,
            int timeoutSeconds) {
        String apiKey = ChatBehaviorBuilder.resolveApiKey(
                resolved.provider(), resolved.providerInstance(),
                request.getTenantId(), request.getProjectId(),
                request.getProcessId(), settingService);
        String baseUrl = ChatBehaviorBuilder.resolveBaseUrl(
                resolved.providerInstance(),
                request.getTenantId(), request.getProjectId(),
                request.getProcessId(), settingService);
        return new AiImageConfig(
                resolved.provider(), resolved.providerInstance(),
                resolved.modelName(), apiKey, baseUrl,
                aspectRatio, timeoutSeconds);
    }

    private String resolveProjectId(GenerateImageRequest request) {
        return request.getProjectId() == null ? "" : request.getProjectId();
    }

    private @Nullable ThinkProcessDocument loadProcess(GenerateImageRequest request) {
        if (request.getProcessId() == null || request.getProcessId().isBlank()) {
            return null;
        }
        return thinkProcessService.findById(request.getProcessId()).orElse(null);
    }

    // ──────────────────── Heartbeat / progress ────────────────────

    private @Nullable ScheduledFuture<?> startHeartbeat(
            @Nullable ThinkProcessDocument process, String alias, long callStart) {
        if (process == null) return null;
        progressEmitter.emitStatus(process, StatusTag.WAITING,
                "Generating image (" + alias + ") …");
        int interval = readHeartbeatInterval(process);
        if (interval <= 0 || scheduler == null) return null;
        return scheduler.scheduleAtFixedRate(() -> {
            long elapsedMs = System.currentTimeMillis() - callStart;
            long elapsedSec = elapsedMs / 1000;
            try {
                progressEmitter.emitStatus(process, StatusTag.WAITING,
                        String.format(Locale.ROOT,
                                "Generating image (%s) … %d:%02d elapsed",
                                alias, elapsedSec / 60, elapsedSec % 60));
            } catch (RuntimeException e) {
                log.debug("FenchurchService: heartbeat emit failed: {}", e.toString());
            }
        }, interval, interval, TimeUnit.SECONDS);
    }

    private int readHeartbeatInterval(ThinkProcessDocument process) {
        String raw = settingService.getStringValueCascade(
                process.getTenantId(), process.getProjectId(),
                process.getId(), SETTING_HEARTBEAT);
        if (raw == null || raw.isBlank()) return DEFAULT_HEARTBEAT_INTERVAL_SECONDS;
        try {
            int parsed = Integer.parseInt(raw.trim());
            return parsed > 0 ? parsed : DEFAULT_HEARTBEAT_INTERVAL_SECONDS;
        } catch (NumberFormatException e) {
            return DEFAULT_HEARTBEAT_INTERVAL_SECONDS;
        }
    }

    private static void cancelHeartbeat(@Nullable Future<?> heartbeat) {
        if (heartbeat == null) return;
        heartbeat.cancel(false);
    }

    // ──────────────────── Call-record bookkeeping ────────────────────

    private void recordSuccess(
            GenerateImageRequest request,
            AiModelResolver.Resolved resolved,
            AiImageConfig config,
            ImageModelInfo modelInfo,
            long callStart,
            long durationMs,
            DocumentDocument committed) {
        ImageCallRecord record = ImageCallRecord.builder()
                .tenantId(request.getTenantId())
                .accountId(request.getUserId())
                .projectId(request.getProjectId())
                .modelUsed(config.fullName())
                .alias(request.getAlias())
                .costUsd(modelInfo.costFor("standard"))
                .qualityTier("standard")
                .outcome("success")
                .at(Instant.ofEpochMilli(callStart))
                .durationMs(durationMs)
                .build();
        callTracker.recordCall(record);
        if (log.isDebugEnabled()) {
            log.debug("Fenchurch: generated image tenant='{}' path='{}' model='{}' "
                            + "size={} duration={}ms",
                    request.getTenantId(), committed.getPath(),
                    config.fullName(), committed.getSize(), durationMs);
        }
    }

    private void recordFailure(
            GenerateImageRequest request,
            AiModelResolver.Resolved resolved,
            AiImageConfig config,
            long callStart,
            Throwable cause) {
        String outcome = outcomeFromError(cause);
        ImageCallRecord record = ImageCallRecord.builder()
                .tenantId(request.getTenantId())
                .accountId(request.getUserId())
                .projectId(request.getProjectId())
                .modelUsed(config.fullName())
                .alias(request.getAlias())
                .outcome(outcome)
                .at(Instant.ofEpochMilli(callStart))
                .durationMs(System.currentTimeMillis() - callStart)
                .build();
        callTracker.recordCall(record);
    }

    private void recordRejected(
            GenerateImageRequest request, String outcome,
            @Nullable AiImageConfig config, @Nullable String model, long durationMs) {
        ImageCallRecord record = ImageCallRecord.builder()
                .tenantId(request.getTenantId())
                .accountId(request.getUserId())
                .projectId(request.getProjectId())
                .modelUsed(config == null
                        ? (model == null ? "" : model)
                        : config.fullName())
                .alias(request.getAlias())
                .outcome(outcome)
                .at(Instant.now())
                .durationMs(durationMs)
                .build();
        callTracker.recordCall(record);
    }

    private static String outcomeFromError(Throwable cause) {
        String msg = cause.getMessage() == null
                ? "" : cause.getMessage().toLowerCase(Locale.ROOT);
        if (msg.contains("timeout") || msg.contains("timed out")) {
            return "timeout";
        }
        if (msg.contains("safety") || msg.contains("content") || msg.contains("policy")) {
            return "content_policy";
        }
        if (cause instanceof java.util.concurrent.CancellationException
                || msg.contains("cancel")) {
            return "cancelled";
        }
        return "provider_error";
    }

    // ──────────────────── Helpers ────────────────────

    private static FenchurchException mapProviderError(
            AiImageException cause, AiImageConfig config) {
        String msg = cause.getMessage() == null
                ? "" : cause.getMessage().toLowerCase(Locale.ROOT);
        FenchurchException.Reason reason = FenchurchException.Reason.PROVIDER_ERROR;
        if (msg.contains("timeout") || msg.contains("timed out")) {
            reason = FenchurchException.Reason.TIMEOUT;
        } else if (msg.contains("safety") || msg.contains("content") || msg.contains("policy")) {
            reason = FenchurchException.Reason.CONTENT_POLICY;
        }
        return new FenchurchException(reason,
                "Fenchurch generation failed for " + config.fullName()
                        + ": " + cause.getMessage(), cause);
    }

    private static @Nullable String stringOrNull(@Nullable Object o) {
        if (o == null) return null;
        String s = o.toString().trim();
        return s.isBlank() ? null : s;
    }
}
