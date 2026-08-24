package de.mhus.vance.brain.sourceconfig;

import java.util.Map;
import org.apache.commons.lang3.StringUtils;
import org.jspecify.annotations.Nullable;

/**
 * One external source instance, as configured in one document.
 *
 * <p>The four common fields are the ones every subsystem needs to decide
 * whether it can build an instance at all; everything else the document
 * declares travels in {@code extras} and is the protocol's business. Values
 * there keep their YAML shape — a list stays a list — because that is the
 * capability the flat setting namespace did not have.
 *
 * @param name        instance id; the document's filename without suffix
 * @param documentPath where it was read from, for diagnostics
 * @param protocol    which protocol serves it; required, an instance without
 *                    one is skipped by the factory
 * @param baseUrl     endpoint root; empty for protocols that need none
 * @param apiKey      credential <em>as written</em> — a {@code {{secret:…}}}
 *                    reference, a {@code {noop}} literal, or absent. Never
 *                    resolved here: resolution needs an invocation scope, and
 *                    a resolved secret in a record is one {@code toString()}
 *                    away from a log file.
 * @param enabled     {@code false} keeps the instance configured but out of
 *                    service; the default is {@code true}, because a document
 *                    that exists is meant to be used
 * @param extras      everything else the document declares
 */
public record SourceConfig(
        String name,
        String documentPath,
        @Nullable String protocol,
        @Nullable String baseUrl,
        @Nullable String apiKey,
        boolean enabled,
        Map<String, Object> extras) {

    public SourceConfig {
        if (StringUtils.isBlank(name)) {
            throw new IllegalArgumentException("name is required");
        }
        documentPath = documentPath == null ? "" : documentPath;
        extras = extras == null ? Map.of() : Map.copyOf(extras);
    }

    /**
     * Where the credential comes from, in the words an operator can act on:
     * {@code _vance/config/feeds/hrafnagud.yaml#apiKey}. Fills the role the
     * setting key played on the SPI configs — it names the place to look when
     * the credential is missing.
     */
    public String credentialLocation() {
        return documentPath + "#apiKey";
    }

    /** An extra as text, or {@code fallback} when absent or blank. */
    public String extraString(String key, String fallback) {
        Object value = extras.get(key);
        if (value == null) {
            return fallback;
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? fallback : text;
    }

    /**
     * How much this source is allowed to learn about the reader. Default
     * {@link ReaderIdentityMode#NONE} — a source that has not been given the
     * permission does not get it.
     *
     * <p>An unknown word resolves to {@code NONE} as well; see
     * {@link ReaderIdentityMode#parse}. Use {@link #hasUnknownReaderIdentity}
     * to tell "not configured" from "misspelled", because only the second one
     * is worth a warning.
     */
    public ReaderIdentityMode readerIdentity() {
        return ReaderIdentityMode.parse(
                extras.get(ReaderIdentityMode.FIELD), ReaderIdentityMode.NONE);
    }

    /** Whether {@code readerIdentity} is set to something unrecognised. */
    public boolean hasUnknownReaderIdentity() {
        Object raw = extras.get(ReaderIdentityMode.FIELD);
        return raw != null
                && StringUtils.isNotBlank(String.valueOf(raw))
                && !ReaderIdentityMode.isKnown(raw);
    }

    /**
     * Whether answers from this source may be cached at all. Default
     * {@code true} — the source states its own policy (a TTL, later an ETag),
     * and this is the local override that can only ever say <em>less</em>.
     *
     * <p>It exists because caching is the one thing an operator cannot fix on
     * the far side: a source that lies about how long its answers stay valid
     * produces stale documents that look like ours.
     */
    public boolean cacheAllowed() {
        return extraBoolean(FIELD_CACHE, true);
    }

    /** Config value governing {@link #cacheAllowed()}. */
    public static final String FIELD_CACHE = "cache";

    /**
     * A copy with {@code readerIdentity} replaced — how a ceiling is applied
     * without the callers downstream having to know a ceiling existed.
     */
    public SourceConfig withReaderIdentity(ReaderIdentityMode mode) {
        Map<String, Object> merged = new java.util.LinkedHashMap<>(extras);
        merged.put(ReaderIdentityMode.FIELD, mode.name().toLowerCase(java.util.Locale.ROOT));
        return new SourceConfig(name, documentPath, protocol, baseUrl, apiKey, enabled, merged);
    }

    /** An extra as boolean, or {@code fallback} when absent. */
    public boolean extraBoolean(String key, boolean fallback) {
        Object value = extras.get(key);
        if (value instanceof Boolean b) {
            return b;
        }
        if (value == null) {
            return fallback;
        }
        String text = String.valueOf(value).trim().toLowerCase(java.util.Locale.ROOT);
        if (text.isEmpty()) {
            return fallback;
        }
        return "true".equals(text) || "1".equals(text) || "yes".equals(text) || "on".equals(text);
    }
}
