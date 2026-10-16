package de.mhus.vance.shared.kit;

import de.mhus.vance.api.kit.KitDescriptorDto;
import de.mhus.vance.api.kit.KitInheritDto;
import de.mhus.vance.api.kit.KitPolicyAction;
import de.mhus.vance.api.kit.KitPolicyDto;
import de.mhus.vance.api.kit.KitPolicyRuleDto;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;

/**
 * The {@code kit.yaml} grammar.
 *
 * <p>Here rather than in the brain because two sides read it: the brain
 * when it installs a kit, the store when a release is submitted. A store
 * that accepted what the brain refuses would publish a kit nobody can
 * install — and since a published version cannot be replaced, the
 * vendor would lose the version number to find out. One parser, so the
 * two cannot drift.
 */
public final class KitDescriptorParser {

    private KitDescriptorParser() {}

    /** Parse a standalone {@code kit.yaml}. */
    public static KitDescriptorDto parse(String yamlText) {
        return parse(loadMap(yamlText, "kit.yaml"), "kit.yaml");
    }

    /**
     * Shared by the standalone {@code kit.yaml} and by the
     * {@code descriptor:} block embedded in an install record — one
     * grammar, so a descriptor never means two different things.
     */
    public static KitDescriptorDto parse(Map<String, Object> map, String label) {
        String name = requireString(map, "name", label);
        String description = requireString(map, "description", label);
        String version = stringOrNull(map.get("version"));
        boolean hasEncryptedSecrets = booleanOrFalse(map.get("hasEncryptedSecrets"));
        boolean artifact = booleanOrFalse(map.get("artifact"));
        boolean installable = booleanOr(map.get("installable"), true);
        boolean sealed = booleanOrFalse(map.get("sealed"));

        // Spec: kits.md §3.2 — a kit that is neither installable nor
        // inheritable cannot be used at all. Reject the descriptor up
        // front rather than failing later with a confusing message.
        if (!installable && sealed) {
            throw new KitException(label + ": 'installable: false' and 'sealed: true' together would make"
                    + " the kit unusable (no direct import, no inherit). Pick one.");
        }

        List<KitInheritDto> inherits = new ArrayList<>();
        Object inheritsRaw = map.get("inherits");
        if (inheritsRaw instanceof List<?> list) {
            for (int i = 0; i < list.size(); i++) {
                Object element = list.get(i);
                if (!(element instanceof Map<?, ?> nested)) {
                    throw new KitException(label + " inherits[" + i + "] must be a map");
                }
                @SuppressWarnings("unchecked")
                Map<String, Object> e = (Map<String, Object>) nested;
                String url = requireString(e, "url", label + " inherits[" + i + "]");
                inherits.add(KitInheritDto.builder()
                        .url(url)
                        .path(stringOrNull(e.get("path")))
                        .branch(stringOrNull(e.get("branch")))
                        .commit(stringOrNull(e.get("commit")))
                        .build());
            }
        } else if (inheritsRaw != null) {
            throw new KitException(label + " inherits must be a list");
        }

        // Files the source wants rendered on arrival. Only ODE sources act
        // on it, but the grammar is parsed wherever a descriptor comes from
        // — a kit checked out of git should not fail to parse just because
        // it is also served by a host.
        List<String> render = new ArrayList<>();
        Object renderRaw = map.get("render");
        if (renderRaw instanceof List<?> list) {
            for (int i = 0; i < list.size(); i++) {
                String path = stringOrNull(list.get(i));
                if (path == null || path.isBlank()) {
                    throw new KitException(label + " render[" + i + "] must be a non-blank path");
                }
                render.add(path.trim());
            }
        } else if (renderRaw != null) {
            throw new KitException(label + " render must be a list of paths");
        }

        return KitDescriptorDto.builder()
                .name(name)
                .description(description)
                .version(version)
                .inherits(inherits)
                .render(render)
                .hasEncryptedSecrets(hasEncryptedSecrets)
                .artifact(artifact)
                .installable(installable)
                .sealed(sealed)
                .vendor(stringOrNull(map.get("vendor")))
                .license(stringOrNull(map.get("license")))
                .homepage(stringOrNull(map.get("homepage")))
                // Delivery-written fields. Parsed wherever a descriptor comes
                // from, because refusing them for git kits would only mean a
                // shop kit re-imported from a checkout loses its purchase
                // trail — and they carry no authority anyway until a
                // signature covers them.
                .licensedTo(stringOrNull(map.get("licensedTo")))
                .purchaseId(stringOrNull(map.get("purchaseId")))
                .licenseExpiresAt(parseInstant(map.get("licenseExpiresAt")))
                // Same grammar as the user's config document — one policy
                // syntax to learn, whether you author kits or install them.
                .policy(map.get("policy") == null ? null : parsePolicy(map.get("policy"), label))
                .build();
    }

    /**
     * Accepts the scalar shorthand ({@code policy: keep}) as well as the
     * full map. The shorthand exists so the common case does not have to
     * pay for the ceremony of an empty rule list.
     */
    @SuppressWarnings("unchecked")
    public static KitPolicyDto parsePolicy(@Nullable Object raw, String label) {
        if (raw == null) return KitPolicyDto.defaults();
        if (!(raw instanceof Map<?, ?> nested)) {
            return KitPolicyDto.builder()
                    .defaultAction(parseAction(raw.toString(), label + " policy"))
                    .build();
        }
        Map<String, Object> map = (Map<String, Object>) nested;
        KitPolicyAction defaultAction = map.get("default") == null
                ? KitPolicyAction.KEEP
                : parseAction(map.get("default").toString(), label + " policy.default");

        List<KitPolicyRuleDto> rules = new ArrayList<>();
        Object rulesRaw = map.get("rules");
        if (rulesRaw instanceof List<?> list) {
            for (int i = 0; i < list.size(); i++) {
                String ruleLabel = label + " policy.rules[" + i + "]";
                if (!(list.get(i) instanceof Map<?, ?> ruleNested)) {
                    throw new KitException(ruleLabel + " must be a map");
                }
                Map<String, Object> e = (Map<String, Object>) ruleNested;
                String document = stringOrNull(e.get("document"));
                String setting = stringOrNull(e.get("setting"));
                if ((document == null) == (setting == null)) {
                    throw new KitException(ruleLabel
                            + " must name exactly one of 'document:' or 'setting:'"
                            + " — document paths and setting keys are separate namespaces."
                            + " Server-tool configs are documents:"
                            + " document: \"server-tools/*.yaml\"");
                }
                Object actionRaw = e.get("action");
                if (actionRaw == null) {
                    throw new KitException(ruleLabel + " is missing 'action'");
                }
                rules.add(KitPolicyRuleDto.builder()
                        .document(document)
                        .setting(setting)
                        .action(parseAction(actionRaw.toString(), ruleLabel + " action"))
                        .build());
            }
        } else if (rulesRaw != null) {
            throw new KitException(label + " policy.rules must be a list");
        }

        return KitPolicyDto.builder().defaultAction(defaultAction).rules(rules).build();
    }

    private static KitPolicyAction parseAction(String raw, String label) {
        try {
            return KitPolicyAction.parse(raw);
        } catch (IllegalArgumentException e) {
            throw new KitException(
                    label + ": unknown action '" + raw.trim() + "' — expected one of keep, overwrite, ignore, merge",
                    e);
        }
    }

    private static Map<String, Object> loadMap(String yamlText, String label) {
        return loadMap(yamlText, label, true);
    }

    /**
     * @param allowDuplicateKeys SnakeYAML's default ({@code true}) lets a
     *        repeated key silently replace the earlier one; the kit files
     *        have always been read that way, so only callers that need to
     *        refuse it pass {@code false}
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> loadMap(String yamlText, String label, boolean allowDuplicateKeys) {
        LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(allowDuplicateKeys);
        Yaml yaml = new Yaml(options);
        Object parsed;
        try {
            parsed = yaml.load(yamlText);
        } catch (RuntimeException e) {
            throw new KitException("Failed to parse " + label + ": " + e.getMessage(), e);
        }
        if (parsed == null) {
            return new LinkedHashMap<>();
        }
        if (!(parsed instanceof Map<?, ?> m)) {
            throw new KitException(label + " must be a top-level map");
        }
        return (Map<String, Object>) m;
    }

    private static String requireString(Map<String, Object> map, String key, String label) {
        Object v = map.get(key);
        if (v == null) {
            throw new KitException(label + ": missing required field '" + key + "'");
        }
        String s = v.toString().trim();
        if (s.isEmpty()) {
            throw new KitException(label + ": field '" + key + "' must not be blank");
        }
        return s;
    }

    private static @Nullable String stringOrNull(@Nullable Object v) {
        if (v == null) return null;
        String s = v.toString().trim();
        return s.isEmpty() ? null : s;
    }

    private static boolean booleanOrFalse(@Nullable Object v) {
        return booleanOr(v, false);
    }

    private static boolean booleanOr(@Nullable Object v, boolean defaultValue) {
        if (v == null) return defaultValue;
        if (v instanceof Boolean b) return b;
        return Boolean.parseBoolean(v.toString().trim());
    }

    private static @Nullable Instant parseInstant(@Nullable Object v) {
        if (v == null) return null;
        if (v instanceof Instant i) return i;
        if (v instanceof java.util.Date d) return d.toInstant();
        try {
            return Instant.parse(v.toString().trim());
        } catch (Exception e) {
            return null;
        }
    }
}
