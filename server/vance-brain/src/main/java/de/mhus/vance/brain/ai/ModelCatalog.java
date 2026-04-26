package de.mhus.vance.brain.ai;

import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.yaml.snakeyaml.Yaml;

/**
 * Catalog of {@link ModelInfo} entries indexed by {@code (provider,
 * modelName)}. Loaded from {@code ai-models.yaml} on the classpath at
 * startup.
 *
 * <p>Lookup is case-insensitive on both provider and modelName so
 * {@code "Anthropic"/"Claude-Sonnet-4-5"} resolves to the same record
 * as {@code "anthropic"/"claude-sonnet-4-5"}.
 *
 * <p>Unknown combinations resolve via {@link #lookupOrDefault} to a
 * conservative fallback (8K context, 4K output) and a WARN log line
 * so the gap is visible — better than silently treating an unknown
 * model as if it had infinite room.
 */
@Service
@Slf4j
public class ModelCatalog {

    private static final String RESOURCE = "ai-models.yaml";
    private static final ModelInfo FALLBACK_TEMPLATE = new ModelInfo(
            "?", "?", 8192, 4096);

    private final Map<String, ModelInfo> byKey = new ConcurrentHashMap<>();

    public ModelCatalog() {
        load();
    }

    public Optional<ModelInfo> lookup(String provider, String modelName) {
        if (provider == null || modelName == null) return Optional.empty();
        return Optional.ofNullable(byKey.get(key(provider, modelName)));
    }

    /**
     * Same as {@link #lookup} but returns a conservative default
     * record when nothing matches. Logs a WARN for visibility.
     */
    public ModelInfo lookupOrDefault(String provider, String modelName) {
        return lookup(provider, modelName).orElseGet(() -> {
            log.warn("ModelCatalog: no entry for '{}/{}' — falling back to {}-token context",
                    provider, modelName, FALLBACK_TEMPLATE.contextWindowTokens());
            return new ModelInfo(
                    provider == null ? "?" : provider,
                    modelName == null ? "?" : modelName,
                    FALLBACK_TEMPLATE.contextWindowTokens(),
                    FALLBACK_TEMPLATE.defaultMaxOutputTokens());
        });
    }

    @SuppressWarnings("unchecked")
    private void load() {
        ClassPathResource resource = new ClassPathResource(RESOURCE);
        if (!resource.exists()) {
            log.warn("ModelCatalog: '{}' not found on classpath — catalog is empty", RESOURCE);
            return;
        }
        Yaml yaml = new Yaml();
        Map<String, Object> root;
        try (InputStream in = resource.getInputStream()) {
            Object parsed = yaml.load(in);
            if (!(parsed instanceof Map<?, ?> m)) {
                log.warn("ModelCatalog: '{}' top level is not a map", RESOURCE);
                return;
            }
            root = (Map<String, Object>) m;
        } catch (IOException e) {
            log.warn("ModelCatalog: failed to read '{}': {}", RESOURCE, e.toString());
            return;
        } catch (RuntimeException e) {
            log.warn("ModelCatalog: failed to parse '{}': {}", RESOURCE, e.toString());
            return;
        }

        int loaded = 0;
        for (Map.Entry<String, Object> providerEntry : root.entrySet()) {
            String provider = providerEntry.getKey();
            if (!(providerEntry.getValue() instanceof Map<?, ?> models)) {
                log.warn("ModelCatalog: provider '{}' has no model map — skipped", provider);
                continue;
            }
            for (Map.Entry<?, ?> modelEntry : models.entrySet()) {
                String modelName = modelEntry.getKey().toString();
                if (!(modelEntry.getValue() instanceof Map<?, ?> spec)) {
                    log.warn("ModelCatalog: '{}/{}' is not a map — skipped", provider, modelName);
                    continue;
                }
                int ctx = readInt(spec.get("contextWindowTokens"),
                        FALLBACK_TEMPLATE.contextWindowTokens());
                int out = readInt(spec.get("defaultMaxOutputTokens"),
                        FALLBACK_TEMPLATE.defaultMaxOutputTokens());
                ModelInfo info = new ModelInfo(provider, modelName, ctx, out);
                byKey.put(key(provider, modelName), info);
                loaded++;
            }
        }
        log.info("ModelCatalog: loaded {} model entries from '{}'", loaded, RESOURCE);
    }

    private static int readInt(Object raw, int fallback) {
        if (raw instanceof Number n) return n.intValue();
        if (raw instanceof String s) {
            try {
                return Integer.parseInt(s.trim());
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    private static String key(String provider, String modelName) {
        return provider.toLowerCase(Locale.ROOT) + "/" + modelName.toLowerCase(Locale.ROOT);
    }
}
