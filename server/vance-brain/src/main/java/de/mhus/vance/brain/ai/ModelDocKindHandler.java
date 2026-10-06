package de.mhus.vance.brain.ai;

import de.mhus.vance.shared.document.kind.KindHandler;
import de.mhus.vance.shared.document.kind.validate.Finding;
import de.mhus.vance.shared.document.kind.validate.KindValidationContext;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import org.yaml.snakeyaml.Yaml;

/**
 * {@link KindHandler} for the {@code vance-model} kind — the operator-managed
 * model + provider documents of the {@link ModelCatalog} under
 * {@code _vance/model/<providerInstance>/…} (spec
 * {@code specification/public/llm-resource-management.md} §3a).
 *
 * <p><b>Location, not body, is the marker</b> — same rule as the three
 * source-config kinds, and for the same reason: a provider sidecar
 * ({@code _provider.yaml}) and a model document are different shapes in one
 * tree, so a body detector cannot claim narrowly. {@code detectsPath} claims
 * {@code _vance/model/} only — the sibling {@code _vance/model-auto/} tree is
 * discovery-owned ("never touched by hand") and stays out.
 *
 * <p><b>Validation is deliberately light — partial documents are legal.</b>
 * Unlike the source-config kinds (where a finding means the factory would
 * silently drop the instance), {@link ModelCatalog} never drops: it
 * deep-merges per field and falls back to conservative defaults for whatever
 * is missing. The findings here mean what the catalog would <em>silently
 * default away</em>: a non-mapping body is skipped with WARN, a
 * {@code contextWindowTokens} that does not parse as a number becomes the 8K
 * fallback nobody wanted. Type errors, in other words — not presence errors.
 * The provider sidecar is the one exception: without a {@code wireType} the
 * instance is unusable, so that one is an ERROR.
 */
@Service
public class ModelDocKindHandler implements KindHandler {

    public static final String KIND = "vance-model";

    /** Provider metadata sidecar inside a provider directory. */
    static final String PROVIDER_FILE = "_provider.yaml";

    private static final Set<String> KNOWN_SIZES = Set.of("SMALL", "LARGE");

    private static final Set<String> NUMERIC_FIELDS = Set.of(
            "contextWindowTokens",
            "defaultMaxOutputTokens",
            "timeoutSeconds",
            "actionLoopCorrections",
            "maxTools",
            "minCacheableInputTokens");

    @Override
    public String getName() {
        return KIND;
    }

    /**
     * The catalog tree is the marker: every document under
     * {@code _vance/model/} is a model or provider document — see the class
     * javadoc for why {@code detects(String)} stays unimplemented and why the
     * prefix keeps {@code _vance/model-auto/} out.
     */
    @Override
    public boolean detectsPath(String documentPath) {
        return documentPath.startsWith(ModelCatalog.MODEL_PATH_PREFIX);
    }

    @Override
    public List<Finding> validate(String content, KindValidationContext ctx) {
        String target = StringUtils.isBlank(ctx.docPath()) ? KIND : ctx.docPath();
        Object parsed;
        try {
            parsed = new Yaml().load(content);
        } catch (RuntimeException e) {
            return List.of(Finding.error(target, "model-doc-parse", e.getMessage()));
        }
        if (parsed == null || StringUtils.isBlank(content)) {
            // An empty override document is legal — it inherits everything.
            return List.of();
        }
        if (!(parsed instanceof Map<?, ?> map)) {
            return List.of(
                    Finding.error(target, "model-doc-mapping", "a model catalog document must be a YAML mapping"));
        }

        List<Finding> findings = new ArrayList<>();
        boolean providerSidecar = ctx.docPath() != null && ctx.docPath().endsWith(PROVIDER_FILE);
        if (providerSidecar) {
            validateProvider(map, target, findings);
        } else {
            validateModel(map, target, findings);
        }
        return findings;
    }

    /**
     * Provider sidecar: {@code wireType} decides how the instance speaks, so
     * it is the one field without which nothing works.
     */
    private static void validateProvider(Map<?, ?> map, String target, List<Finding> findings) {
        Object wireType = map.get("wireType");
        if (wireType == null || StringUtils.isBlank(String.valueOf(wireType))) {
            findings.add(Finding.error(
                    target,
                    "model-provider-wire-type",
                    "no wireType set — the provider instance cannot resolve any model"));
        }
    }

    /**
     * Model document: type errors only — a missing field is a legal partial
     * override, a field that cannot parse is a default nobody wanted.
     */
    private static void validateModel(Map<?, ?> map, String target, List<Finding> findings) {
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            String key = String.valueOf(entry.getKey());
            Object value = entry.getValue();
            if (value == null) {
                continue;
            }
            if (NUMERIC_FIELDS.contains(key) && !(value instanceof Number)) {
                findings.add(Finding.warning(
                        target,
                        "model-doc-number",
                        "'" + key + "' is not a number — the catalog falls back to its default"));
            } else if ("size".equals(key)
                    && !KNOWN_SIZES.contains(String.valueOf(value).trim().toUpperCase(java.util.Locale.ROOT))) {
                findings.add(Finding.warning(
                        target,
                        "model-doc-size",
                        "'" + value + "' is not a known size (SMALL, LARGE) — the catalog falls back"));
            } else if ("capabilities".equals(key)) {
                validateCapabilities(value, target, findings);
            } else if ("pricing".equals(key) && !(value instanceof Map)) {
                findings.add(Finding.warning(
                        target, "model-doc-pricing", "'pricing' is not a mapping — the catalog ignores it"));
            }
        }
    }

    private static void validateCapabilities(Object value, String target, List<Finding> findings) {
        if (!(value instanceof List<?> list)) {
            findings.add(Finding.warning(
                    target, "model-doc-capabilities", "'capabilities' is not a list — the catalog ignores it"));
            return;
        }
        // The catalog parses with ModelCapability.fromString — case and
        // kebab-tolerant — so the check accepts what the runtime accepts,
        // including the lowercase spelling the shipped documents carry.
        for (Object cap : list) {
            boolean known = cap != null
                    && ModelCapability.fromString(String.valueOf(cap)).isPresent();
            if (!known) {
                findings.add(Finding.warning(
                        target,
                        "model-doc-capability-unknown",
                        "'" + cap + "' is not a known capability — the catalog ignores it"));
            }
        }
    }
}
