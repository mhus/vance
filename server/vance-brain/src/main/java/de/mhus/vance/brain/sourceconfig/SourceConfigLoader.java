package de.mhus.vance.brain.sourceconfig;

import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.shared.document.LookupResult;
import de.mhus.vance.shared.home.HomeBootstrapService;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.error.YAMLException;

/**
 * Reads the source-configuration documents of one project, one YAML per
 * instance, through the standard document cascade
 * {@code project → _tenant → classpath}.
 *
 * <p><b>Whole-document override, not per field.</b> A project document with the
 * same filename replaces the {@code _tenant} one entirely. That is how every
 * other document loader in the tree behaves, and it is the honest model here:
 * a source has one origin. (The setting cascade this replaced merged per key,
 * so the expectation travels — hence the explicit test.)
 *
 * <p><b>A broken entry is skipped, never fatal.</b> Unparseable YAML, a
 * non-mapping body, a document that is not a YAML at all: logged and left out,
 * so one bad file cannot take a project's other sources down with it. The
 * factory applies the same rule to unknown protocols.
 *
 * <p>No cache — the factories hold the assembled instances and are the ones
 * that know when to drop them.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SourceConfigLoader {

    /** Reserved top-level keys; everything else lands in {@code extras}. */
    private static final String KEY_PROTOCOL = "protocol";
    private static final String KEY_BASE_URL = "baseUrl";
    private static final String KEY_API_KEY = "apiKey";
    private static final String KEY_ENABLED = "enabled";

    private final DocumentService documentService;

    /**
     * Every configured instance under {@code pathPrefix}, cascade-merged.
     * Order is the cascade application order and carries no meaning.
     *
     * <p>{@code readerIdentity} is capped here against the tenant-level
     * document of the same name — see {@link #applyTenantCeiling}.
     */
    public List<SourceConfig> load(String tenantId, String projectId, String pathPrefix) {
        Map<String, LookupResult> hits =
                documentService.listByPrefixCascade(tenantId, projectId, pathPrefix);
        if (hits.isEmpty()) {
            return List.of();
        }
        List<SourceConfig> out = new ArrayList<>(hits.size());
        for (Map.Entry<String, LookupResult> hit : hits.entrySet()) {
            String path = hit.getKey();
            String name = SourceConfigPaths.nameFromPath(pathPrefix, path);
            if (name == null) {
                // A README, a stray .md — not an error, just not ours.
                log.debug("SourceConfig: ignoring non-config path '{}'", path);
                continue;
            }
            try {
                SourceConfig config = parse(name, path, hit.getValue().content());
                warnOnRetiredKeys(config);
                out.add(config);
            } catch (RuntimeException e) {
                log.warn("SourceConfig: skipping '{}' ({}): {}",
                        path, hit.getValue().source(), e.getMessage());
            }
        }
        return List.copyOf(applyTenantCeiling(tenantId, projectId, pathPrefix, out));
    }

    /**
     * Say something when a document uses a key we retired.
     *
     * <p>Central rather than per subsystem, and for the same reason the ceiling
     * is: a document that still says {@code sendActor: true} is otherwise
     * indistinguishable from one that says nothing — the new field defaults to
     * {@code none}, so the old switch does not fail, it silently stops working.
     * That is the exact case {@code warnOnUnsupportedReaderIdentity} already
     * covers for a misspelled <em>new</em> value; an obsolete key deserves the
     * same line, in every subsystem that reads a source document.
     */
    private static void warnOnRetiredKeys(SourceConfig config) {
        if (config.extras().containsKey(ReaderIdentityMode.RETIRED_FIELD)) {
            log.warn("SourceConfig: '{}' still sets '{}' — that switch was replaced by "
                            + "'{}: pseudonym' and now has no effect at all",
                    config.documentPath(), ReaderIdentityMode.RETIRED_FIELD,
                    ReaderIdentityMode.FIELD);
        }
    }

    /**
     * Cap each config's {@code readerIdentity} at what the tenant-level
     * document of the same name allows.
     *
     * <p>The document cascade is whole-document override: a project file
     * replaces the {@code _tenant} one entirely. For every other field that is
     * right — a source has one origin. For this one it is not, because it
     * would mean anyone who may write a project configuration can widen a
     * privacy decision the tenant made. Ceilings only ever restrict, so this
     * runs centrally rather than in each subsystem: a ceiling somebody can
     * forget to apply is not a ceiling.
     *
     * <p>Same shape as the foot sandbox, where the project-local policy file
     * may only tighten the central one.
     *
     * <p><b>Known limit:</b> the ceiling binds only where the tenant has a
     * document of that name. A source configured solely in a project has no
     * tenant statement above it and therefore no cap — an explicit restriction
     * has to be written down to exist. A tenant that wants a blanket ceiling
     * needs a deployment-level policy, which is deliberately not built here.
     */
    private List<SourceConfig> applyTenantCeiling(
            String tenantId, String projectId, String pathPrefix, List<SourceConfig> configs) {
        if (HomeBootstrapService.TENANT_PROJECT_NAME.equals(projectId)) {
            // Nothing sits above the tenant level — and this is also what
            // keeps the recursion below one deep.
            return configs;
        }
        if (configs.stream().allMatch(c -> c.readerIdentity() == ReaderIdentityMode.NONE)) {
            // The common case by far: nothing to cap, so no second cascade read.
            return configs;
        }
        Map<String, ReaderIdentityMode> ceilings = new LinkedHashMap<>();
        for (SourceConfig tenantConfig
                : load(tenantId, HomeBootstrapService.TENANT_PROJECT_NAME, pathPrefix)) {
            ceilings.put(tenantConfig.name(), tenantConfig.readerIdentity());
        }
        List<SourceConfig> capped = new ArrayList<>(configs.size());
        for (SourceConfig config : configs) {
            ReaderIdentityMode ceiling = ceilings.get(config.name());
            if (ceiling == null) {
                capped.add(config);
                continue;
            }
            ReaderIdentityMode effective = config.readerIdentity().atMost(ceiling);
            if (effective != config.readerIdentity()) {
                log.info("SourceConfig: '{}' asks for readerIdentity={} but the tenant document "
                                + "allows at most {} — using {}",
                        config.documentPath(), config.readerIdentity(), ceiling, effective);
                capped.add(config.withReaderIdentity(effective));
            } else {
                capped.add(config);
            }
        }
        return capped;
    }

    /**
     * Parse one document body. Public so an admin/validation path can check a
     * body before it is written.
     *
     * @throws SourceConfigParseException when the body is not a YAML mapping
     */
    public SourceConfig parse(String name, String documentPath, @Nullable String content) {
        Object raw;
        try {
            raw = new Yaml().load(content == null ? "" : content);
        } catch (YAMLException e) {
            throw new SourceConfigParseException("not valid YAML: " + e.getMessage(), e);
        }
        if (raw == null) {
            throw new SourceConfigParseException("document is empty");
        }
        if (!(raw instanceof Map<?, ?> map)) {
            throw new SourceConfigParseException(
                    "expected a YAML mapping, found " + raw.getClass().getSimpleName());
        }

        String protocol = null;
        String baseUrl = null;
        String apiKey = null;
        boolean enabled = true;
        Map<String, Object> extras = new LinkedHashMap<>();

        for (Map.Entry<?, ?> entry : map.entrySet()) {
            String key = String.valueOf(entry.getKey());
            Object value = entry.getValue();
            switch (key) {
                case KEY_PROTOCOL -> protocol = text(value);
                case KEY_BASE_URL -> baseUrl = text(value);
                case KEY_API_KEY -> apiKey = text(value);
                case KEY_ENABLED -> enabled = bool(value, true);
                default -> {
                    if (value != null) {
                        extras.put(key, value);
                    }
                }
            }
        }
        return new SourceConfig(name, documentPath, protocol, baseUrl, apiKey, enabled, extras);
    }

    private static @Nullable String text(@Nullable Object value) {
        if (value == null) {
            return null;
        }
        String s = String.valueOf(value).trim();
        return s.isEmpty() ? null : s;
    }

    private static boolean bool(@Nullable Object value, boolean fallback) {
        if (value instanceof Boolean b) {
            return b;
        }
        String s = text(value);
        if (s == null) {
            return fallback;
        }
        String lower = s.toLowerCase(java.util.Locale.ROOT);
        return "true".equals(lower) || "1".equals(lower) || "yes".equals(lower) || "on".equals(lower);
    }
}
