package de.mhus.vance.brain.kit.provisioning;

import de.mhus.vance.api.kit.KitProvisioningAuthority;
import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.shared.kit.KitException;
import de.mhus.vance.toolpack.ToolInvocationContext;
import de.mhus.vance.toolpack.core.SecretResolver;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.yaml.snakeyaml.Yaml;

/**
 * Reads a project's {@code _vance/kits/provisioning.yaml}.
 *
 * <p><b>Read without the cascade, on purpose.</b> Every other kit
 * document is looked up through the project → {@code _tenant} → bundled
 * chain; this one must not be. An entry in {@code _tenant} would be
 * visible in every project of the tenant and would therefore install
 * into every one of them — the opposite of a per-project decision. The
 * precedent for „applies here" ≠ „visible here" is
 * {@code ModelDiscoveryService}, which reads
 * {@code ai.provider.<instance>.*} non-cascaded for the same reason.
 *
 * <p><b>A malformed document is not silently ignored.</b> Somebody wrote
 * something they believe is in effect; defaulting to „no provisioning"
 * would apply the opposite of their intent, and quietly.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class KitProvisioningLoader {

    /** Where the desired-list declaration lives, in the project itself. */
    public static final String PROVISIONING_PATH = "_vance/kits/provisioning.yaml";

    private final DocumentService documentService;
    private final SecretResolver secretResolver;

    /**
     * Entries declared for this project, or empty when the project has no
     * provisioning document — which is the normal case.
     */
    public List<KitProvisioningEntry> load(String tenantId, String projectId) {
        Optional<DocumentDocument> doc =
                documentService.findByPath(tenantId, projectId, PROVISIONING_PATH);
        if (doc.isEmpty()) return List.of();
        String content = readText(doc.get());
        if (content == null || content.isBlank()) return List.of();
        return parse(content, tenantId, projectId);
    }

    /**
     * Who wrote this project's provisioning document, or null when there
     * is none.
     *
     * <p>Used to address a notice. The person who declared a source is the
     * person who cares that it diverged — and no other candidate is
     * better: a project has no „owner" field, and telling every tenant
     * admin would turn one project's configuration into everybody's inbox.
     *
     * <p>Looked up lazily, only when there is something to report, so the
     * common „nothing diverged" tick pays nothing for it.
     */
    public @Nullable String declaredBy(String tenantId, String projectId) {
        return documentService.findByPath(tenantId, projectId, PROVISIONING_PATH)
                .map(DocumentDocument::getCreatedBy)
                .orElse(null);
    }

    @SuppressWarnings("unchecked")
    List<KitProvisioningEntry> parse(String yamlText, String tenantId, String projectId) {
        Object loaded;
        try {
            loaded = new Yaml().load(yamlText);
        } catch (RuntimeException e) {
            throw new KitException(PROVISIONING_PATH + " is not valid yaml: " + e.getMessage(), e);
        }
        if (loaded == null) return List.of();
        if (!(loaded instanceof Map<?, ?> root)) {
            throw new KitException(PROVISIONING_PATH + " must be a map with a"
                    + " 'provisioning:' list at the top level");
        }
        Object list = ((Map<String, Object>) root).get("provisioning");
        if (list == null) return List.of();
        if (!(list instanceof List<?> entries)) {
            throw new KitException(PROVISIONING_PATH + " 'provisioning' must be a list");
        }

        // One context for the whole document: secret references resolve in this
        // project's cascade, which is where an operator would put the token.
        ToolInvocationContext ctx =
                new ToolInvocationContext(tenantId, projectId, null, null, null);

        List<KitProvisioningEntry> result = new ArrayList<>(entries.size());
        for (int i = 0; i < entries.size(); i++) {
            String label = PROVISIONING_PATH + " provisioning[" + i + "]";
            if (!(entries.get(i) instanceof Map<?, ?> raw)) {
                throw new KitException(label + " must be a map");
            }
            Map<String, Object> entry = (Map<String, Object>) raw;
            result.add(new KitProvisioningEntry(
                    requireString(entry, "type", label),
                    requireString(entry, "url", label),
                    // The connector path deliberately: the loader is compiled
                    // server code, not a dynamic element, so a PASSWORD-typed
                    // target is legitimate here. The restrictive default would
                    // substitute an empty string and produce an opaque 401.
                    secretResolver.resolveForConnector(stringOrNull(entry.get("token")), ctx),
                    authority(entry.get("authority"), label),
                    params(entry.get("params"), label)));
        }
        log.debug("Provisioning of {}/{}: {} entry/entries", tenantId, projectId, result.size());
        return List.copyOf(result);
    }

    private static KitProvisioningAuthority authority(@Nullable Object raw, String label) {
        String text = stringOrNull(raw);
        if (text == null || text.isBlank()) return KitProvisioningAuthority.defaultLevel();
        try {
            return KitProvisioningAuthority.valueOf(text.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            // Not defaulted to NOTIFY: a typo in `manage` would silently give
            // the opposite of what was written, and the writer would find out
            // by nothing happening.
            throw new KitException(label + " has an unknown authority '" + text
                    + "' — one of notify, update, manage");
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> params(@Nullable Object raw, String label) {
        if (raw == null) return Map.of();
        if (!(raw instanceof Map<?, ?> map)) {
            throw new KitException(label + " 'params' must be a map");
        }
        // Copied into a LinkedHashMap rather than passed through: what SnakeYAML
        // returns is mutable and shared with the parsed tree.
        return new LinkedHashMap<>((Map<String, Object>) map);
    }

    private static String requireString(Map<String, Object> map, String key, String label) {
        String value = stringOrNull(map.get(key));
        if (value == null || value.isBlank()) {
            throw new KitException(label + " needs a '" + key + "'");
        }
        return value.trim();
    }

    private static @Nullable String stringOrNull(@Nullable Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private @Nullable String readText(DocumentDocument doc) {
        String content = documentService.readContent(doc);
        if (content != null) return content;
        try (var in = documentService.loadContent(doc)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new KitException("failed to read " + PROVISIONING_PATH, e);
        }
    }
}
