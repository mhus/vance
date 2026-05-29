package de.mhus.vance.shared.document.kind;

import java.util.LinkedHashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * Parser and serialiser for {@code kind: application} document
 * bodies. JSON and YAML; no Markdown (a Mermaid-style fence makes
 * no sense for an app manifest).
 *
 * <p>The header carries both {@code kind} and {@code app} as scalar
 * meta keys, so {@code DocumentDocument.headers.app} is populated
 * automatically through the standard
 * {@link de.mhus.vance.shared.document.JsonHeaderStrategy} /
 * {@link de.mhus.vance.shared.document.YamlHeaderStrategy} machinery.
 *
 * <p>Body parsing is deliberately structural — app-specific typing
 * (lanes, gantt config, …) lives in companion helpers like
 * {@code CalendarsAppConfig} so this codec stays domain-agnostic.
 */
public final class ApplicationCodec {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<LinkedHashMap<String, Object>> JSON_MAP =
            new TypeReference<>() {};

    private ApplicationCodec() {
        // utility class
    }

    public static ApplicationDocument parse(String body, @Nullable String mimeType) {
        if (isJson(mimeType)) return parseJson(body);
        if (isYaml(mimeType)) return parseYaml(body);
        throw new KindCodecException("Unsupported mime type for application: " + mimeType);
    }

    public static String serialize(ApplicationDocument doc, @Nullable String mimeType) {
        if (isJson(mimeType)) return serializeJson(doc);
        if (isYaml(mimeType)) return serializeYaml(doc);
        throw new KindCodecException("Unsupported mime type for application: " + mimeType);
    }

    public static boolean supports(@Nullable String mimeType) {
        return isJson(mimeType) || isYaml(mimeType);
    }

    private static boolean isJson(@Nullable String mime) {
        return "application/json".equals(mime);
    }

    private static boolean isYaml(@Nullable String mime) {
        return "application/yaml".equals(mime)
                || "application/x-yaml".equals(mime)
                || "text/yaml".equals(mime)
                || "text/x-yaml".equals(mime);
    }

    // ── JSON / YAML ────────────────────────────────────────────────

    private static ApplicationDocument parseJson(String body) {
        if (body.isBlank()) return ApplicationDocument.empty("");
        Map<String, Object> parsed;
        try {
            parsed = JSON.readValue(body, JSON_MAP);
        } catch (JacksonException e) {
            throw new KindCodecException("Invalid JSON: " + e.getOriginalMessage(), e);
        }
        if (parsed == null) throw new KindCodecException("Top-level JSON must be an object");
        return promoteToDocument(KindHeaderCodec.unwrapJsonMeta(parsed));
    }

    private static ApplicationDocument parseYaml(String body) {
        if (body.isBlank()) return ApplicationDocument.empty("");
        return promoteToDocument(KindHeaderCodec.parseYamlBody(body));
    }

    private static String serializeJson(ApplicationDocument doc) {
        Map<String, Object> headerExtra = new LinkedHashMap<>();
        if (doc.app() != null && !doc.app().isBlank()) headerExtra.put("app", doc.app());
        // wrapJsonMeta only takes kind + body, so we build the
        // wrapped form by inlining the scalar header extras into
        // the $meta block manually for JSON (mirrors what
        // dumpYamlBody(kind, body, headerExtra) does for YAML).
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("kind", canonicalKind(doc));
        for (Map.Entry<String, Object> e : headerExtra.entrySet()) meta.put(e.getKey(), e.getValue());
        Map<String, Object> wrapped = new LinkedHashMap<>();
        wrapped.put(KindHeaderCodec.META_KEY, meta);
        wrapped.putAll(buildBody(doc));
        try {
            return JSON.writerWithDefaultPrettyPrinter().writeValueAsString(wrapped) + "\n";
        } catch (JacksonException e) {
            throw new KindCodecException("Failed to write JSON: " + e.getOriginalMessage(), e);
        }
    }

    private static String serializeYaml(ApplicationDocument doc) {
        Map<String, Object> headerExtra = new LinkedHashMap<>();
        if (doc.app() != null && !doc.app().isBlank()) headerExtra.put("app", doc.app());
        return KindHeaderCodec.dumpYamlBody(canonicalKind(doc), buildBody(doc), headerExtra);
    }

    // ── Promotion ──────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private static ApplicationDocument promoteToDocument(Map<String, Object> obj) {
        String kind = (obj.get("kind") instanceof String s) ? s : "";
        String app = (obj.get("app") instanceof String s2) ? s2 : "";
        String title = (obj.get("title") instanceof String s3 && !s3.isBlank()) ? s3 : null;
        String description = (obj.get("description") instanceof String s4 && !s4.isBlank()) ? s4 : null;

        // App-specific config lives nested under the app's own name —
        // e.g. config.calendar = { window, lanes, gantt, conflicts }.
        // We rebuild a top-level config map containing every key that
        // isn't part of the manifest's reserved scalar set, so future
        // app faces can sit beside calendar without schema work.
        Map<String, Object> config = new LinkedHashMap<>();
        Map<String, Object> extra = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : obj.entrySet()) {
            String key = e.getKey();
            if (isReservedTopKey(key)) continue;
            if (e.getValue() instanceof Map<?, ?>) {
                config.put(key, (Map<String, Object>) e.getValue());
            } else {
                extra.put(key, e.getValue());
            }
        }
        return new ApplicationDocument(
                kind.isEmpty() ? "application" : kind,
                app, title, description, config, extra);
    }

    private static boolean isReservedTopKey(String key) {
        return switch (key) {
            case "kind", "app", "title", "description" -> true;
            default -> false;
        };
    }

    // ── Body builder ───────────────────────────────────────────────

    private static Map<String, Object> buildBody(ApplicationDocument doc) {
        Map<String, Object> body = new LinkedHashMap<>();
        if (doc.title() != null) body.put("title", doc.title());
        if (doc.description() != null) body.put("description", doc.description());
        // Restore the app-config blocks at the top level.
        for (Map.Entry<String, Object> e : doc.config().entrySet()) {
            if (!body.containsKey(e.getKey())) body.put(e.getKey(), e.getValue());
        }
        for (Map.Entry<String, Object> e : doc.extra().entrySet()) {
            if (!body.containsKey(e.getKey())) body.put(e.getKey(), e.getValue());
        }
        return body;
    }

    private static String canonicalKind(ApplicationDocument doc) {
        return (doc.kind() == null || doc.kind().isBlank()) ? "application" : doc.kind();
    }
}
