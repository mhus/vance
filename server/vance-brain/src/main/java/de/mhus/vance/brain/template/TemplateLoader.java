package de.mhus.vance.brain.template;

import de.mhus.vance.api.form.FormFieldDto;
import de.mhus.vance.brain.prompt.PromptTemplateException;
import de.mhus.vance.brain.prompt.PromptTemplateRenderer;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.shared.document.LookupResult;
import de.mhus.vance.shared.form.FormFieldYamlParser;
import de.mhus.vance.shared.home.HomeBootstrapService;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.yaml.snakeyaml.Yaml;

/**
 * Loads and parses document templates through the three-tier cascade
 * {@code project → _tenant → classpath:vance-defaults/_vance/templates/}.
 *
 * <p>Each template is a pair of documents under {@code _vance/templates/}:
 * the definition {@code <name>.yaml} and a body {@code <name>.tmpl.<ext>}.
 * Both are resolved through the standard {@link DocumentService} cascade;
 * shipping them together in one tier is the authoring convention, but a
 * partial override (definition only) transparently reuses a lower tier's
 * body — the body is paired to the definition's tier when present, else
 * the innermost body available.
 *
 * <p>Parse failures on individual entries are logged on WARN and skipped
 * in {@link #listAll} — one broken template must not hide the rest.
 * {@link #load} throws so the controller can surface a clean 500.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TemplateLoader {

    /** Path prefix used for template documents in any cascade tier. */
    public static final String TEMPLATE_PATH_PREFIX = "_vance/templates/";

    /** Definition-file suffix. */
    public static final String DEF_SUFFIX = ".yaml";

    /** Infix that marks a body file: {@code <stem>.tmpl.<ext>}. */
    public static final String BODY_INFIX = ".tmpl.";

    private final DocumentService documentService;
    private final PromptTemplateRenderer templateRenderer;

    /** Resolve {@code name} against the cascade. Empty when no layer carries the definition. */
    public Optional<ResolvedTemplate> load(
            String tenantId, @Nullable String projectId, String name) {
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }
        String normalizedName = name.toLowerCase(Locale.ROOT).trim();
        String effectiveProject = effectiveProject(projectId);
        Map<String, LookupResult> all =
                documentService.listByPrefixCascade(tenantId, effectiveProject, TEMPLATE_PATH_PREFIX);

        LookupResult def = all.get(TEMPLATE_PATH_PREFIX + normalizedName + DEF_SUFFIX);
        if (def == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(buildResolved(normalizedName, def, all));
        } catch (RuntimeException e) {
            throw new TemplateParseException(
                    "Failed to load template '" + name + "' from " + def.source()
                            + " at '" + def.path() + "': " + e.getMessage(), e);
        }
    }

    /**
     * Merged listing across the cascade. Innermost layer wins per name.
     * Templates whose body is missing or whose definition is malformed
     * are skipped with a WARN. The {@code availableIn} glob filter is
     * applied against the requested project context.
     */
    public List<ResolvedTemplate> listAll(String tenantId, @Nullable String projectId) {
        String effectiveProject = effectiveProject(projectId);
        Map<String, LookupResult> all =
                documentService.listByPrefixCascade(tenantId, effectiveProject, TEMPLATE_PATH_PREFIX);

        Map<String, ResolvedTemplate> byName = new LinkedHashMap<>();
        for (Map.Entry<String, LookupResult> e : all.entrySet()) {
            String filename = fileName(e.getKey());
            if (!isDefinition(filename)) continue;
            String name = filename.substring(0, filename.length() - DEF_SUFFIX.length());
            if (name.isBlank()) continue;
            try {
                byName.put(name, buildResolved(name, e.getValue(), all));
            } catch (RuntimeException ex) {
                log.warn("TemplateLoader: skipping malformed template path='{}' source={}: {}",
                        e.getKey(), e.getValue().source(), ex.getMessage());
            }
        }

        List<ResolvedTemplate> filtered = new ArrayList<>(byName.size());
        for (ResolvedTemplate t : byName.values()) {
            if (isAvailableIn(t.availableIn(), effectiveProject)) {
                filtered.add(t);
            }
        }
        return filtered;
    }

    // ──────────────────── Parsing ────────────────────

    @SuppressWarnings("unchecked")
    private ResolvedTemplate buildResolved(
            String name, LookupResult def, Map<String, LookupResult> all) {
        Yaml yaml = new Yaml();
        Object parsed = yaml.load(def.content());
        if (!(parsed instanceof Map<?, ?> rawMap)) {
            throw new IllegalStateException("template definition must have a top-level map");
        }
        Map<String, Object> spec = (Map<String, Object>) rawMap;

        Map<String, String> title =
                FormFieldYamlParser.requiredLocalizedText(spec.get("title"), "title");
        Map<String, String> description =
                FormFieldYamlParser.requiredLocalizedText(spec.get("description"), "description");
        String icon = FormFieldYamlParser.optionalString(spec.get("icon"));
        List<String> tags = parseTags(spec.get("tags"));

        NamePolicy namePolicy = parseNamePolicy(spec.get("name"));
        if (namePolicy.defaultTemplate() != null) {
            compileTemplate(namePolicy.defaultTemplate(), "name.default");
        }

        String typeOverride = FormFieldYamlParser.optionalString(spec.get("type"));
        List<FormFieldDto> fields = FormFieldYamlParser.parseFields(spec.get("fields"), "fields");
        String bodyOverride = FormFieldYamlParser.optionalString(spec.get("body"));
        List<String> availableIn = parseAvailableIn(spec.get("availableIn"));

        TemplateSource source = mapSource(def.source());
        LookupResult body = findBody(all, name, bodyOverride, source);
        if (body == null) {
            throw new IllegalStateException(
                    "no body file found (expected '" + name + BODY_INFIX + "*'"
                            + (bodyOverride != null ? " or override '" + bodyOverride + "'" : "") + ")");
        }
        // Compile the body so a broken Pebble template fails at load time,
        // not on the first apply (fail-fast, like wizard promptTemplate).
        compileTemplate(body.content(), "body");

        return new ResolvedTemplate(
                name, title, description, icon, tags,
                namePolicy.mode(), namePolicy.defaultTemplate(), namePolicy.value(),
                typeOverride, fields, availableIn, source,
                body.path(), body.content());
    }

    /**
     * Finds the body entry for {@code name} in the merged listing.
     * Prefers a body from the same cascade tier as the definition
     * ({@code defSource}); otherwise takes the innermost available. When
     * {@code bodyOverride} is set, only that exact filename matches.
     */
    private @Nullable LookupResult findBody(
            Map<String, LookupResult> all,
            String name,
            @Nullable String bodyOverride,
            TemplateSource defSource) {
        List<LookupResult> matches = new ArrayList<>(2);
        for (Map.Entry<String, LookupResult> e : all.entrySet()) {
            String filename = fileName(e.getKey());
            boolean hit = bodyOverride != null
                    ? filename.equals(bodyOverride)
                    : isBody(filename) && name.equals(bodyStem(filename));
            if (hit) matches.add(e.getValue());
        }
        if (matches.isEmpty()) return null;
        for (LookupResult m : matches) {
            if (mapSource(m.source()) == defSource) return m;
        }
        matches.sort((a, b) -> Integer.compare(rank(a.source()), rank(b.source())));
        return matches.get(0);
    }

    private static int rank(LookupResult.Source s) {
        return switch (s) {
            case PROJECT -> 0;
            case VANCE -> 1;
            case RESOURCE -> 2;
        };
    }

    private List<String> parseTags(@Nullable Object raw) {
        if (raw == null) return List.of();
        if (!(raw instanceof List<?> list)) {
            throw new IllegalStateException("'tags' must be a list of strings");
        }
        List<String> out = new ArrayList<>(list.size());
        for (int i = 0; i < list.size(); i++) {
            Object entry = list.get(i);
            if (!(entry instanceof String s) || s.isBlank()) {
                throw new IllegalStateException("'tags[" + i + "]' must be a non-blank string");
            }
            out.add(s.trim());
        }
        return List.copyOf(out);
    }

    private NamePolicy parseNamePolicy(@Nullable Object raw) {
        if (raw == null) {
            return new NamePolicy(TemplateNameMode.FREE, null, null);
        }
        if (!(raw instanceof Map<?, ?> rawMap)) {
            throw new IllegalStateException("'name' must be a map");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> m = (Map<String, Object>) rawMap;
        String modeRaw = FormFieldYamlParser.optionalString(m.get("mode"));
        TemplateNameMode mode = "fixed".equalsIgnoreCase(modeRaw)
                ? TemplateNameMode.FIXED
                : TemplateNameMode.FREE;
        String defaultTemplate = FormFieldYamlParser.optionalString(m.get("default"));
        String value = FormFieldYamlParser.optionalString(m.get("value"));
        if (mode == TemplateNameMode.FIXED) {
            if (value == null) {
                throw new IllegalStateException("'name.value' is required for name.mode=fixed");
            }
            // default is meaningless for a fixed name — drop it.
            return new NamePolicy(TemplateNameMode.FIXED, null, value);
        }
        return new NamePolicy(TemplateNameMode.FREE, defaultTemplate, null);
    }

    private record NamePolicy(
            TemplateNameMode mode,
            @Nullable String defaultTemplate,
            @Nullable String value) {}

    private static List<String> parseAvailableIn(@Nullable Object raw) {
        if (raw == null) {
            return List.of("*");
        }
        if (!(raw instanceof List<?> list)) {
            throw new IllegalStateException("'availableIn' must be a list of glob patterns");
        }
        if (list.isEmpty()) {
            return List.of("*");
        }
        List<String> out = new ArrayList<>(list.size());
        for (int i = 0; i < list.size(); i++) {
            Object entry = list.get(i);
            if (!(entry instanceof String s)) {
                throw new IllegalStateException("'availableIn[" + i + "]' must be a string");
            }
            String trimmed = s.trim();
            if (trimmed.isEmpty()) {
                throw new IllegalStateException("'availableIn[" + i + "]' is blank");
            }
            out.add(trimmed);
        }
        return List.copyOf(out);
    }

    private void compileTemplate(String template, String fieldName) {
        try {
            templateRenderer.compile(template);
        } catch (PromptTemplateException e) {
            throw new IllegalStateException(
                    "'" + fieldName + "' is not a valid Pebble template: " + e.getMessage(), e);
        }
    }

    // ──────────────────── availableIn glob ────────────────────

    /**
     * Matches a template's {@code availableIn} patterns against a project
     * id. {@code "!"}-prefixed entries are excludes; a single exclude
     * match removes the template. If the list has only excludes, a
     * missing positive match is treated as implicit {@code "*"}.
     * (Same semantics as the wizard subsystem — kept local and pure.)
     */
    static boolean isAvailableIn(List<String> patterns, String projectId) {
        if (patterns == null || patterns.isEmpty()) {
            return true;
        }
        boolean hasInclude = false;
        boolean anyIncludeMatched = false;
        for (String pattern : patterns) {
            if (pattern.startsWith("!")) {
                if (globMatch(pattern.substring(1), projectId)) {
                    return false;
                }
            } else {
                hasInclude = true;
                if (globMatch(pattern, projectId)) {
                    anyIncludeMatched = true;
                }
            }
        }
        return !hasInclude || anyIncludeMatched;
    }

    private static boolean globMatch(String pattern, String value) {
        StringBuilder regex = new StringBuilder(pattern.length() + 4);
        regex.append('^');
        StringBuilder literal = new StringBuilder();
        for (int i = 0; i < pattern.length(); i++) {
            char c = pattern.charAt(i);
            if (c == '*') {
                if (literal.length() > 0) {
                    regex.append(Pattern.quote(literal.toString()));
                    literal.setLength(0);
                }
                regex.append("[^/]*");
            } else {
                literal.append(c);
            }
        }
        if (literal.length() > 0) {
            regex.append(Pattern.quote(literal.toString()));
        }
        regex.append('$');
        return Pattern.matches(regex.toString(), value);
    }

    // ──────────────────── Path helpers ────────────────────

    private static String effectiveProject(@Nullable String projectId) {
        return (projectId == null || projectId.isBlank())
                ? HomeBootstrapService.TENANT_PROJECT_NAME
                : projectId;
    }

    private static String fileName(String path) {
        int slash = path.lastIndexOf('/');
        return slash >= 0 ? path.substring(slash + 1) : path;
    }

    /** A definition file: {@code *.yaml} that is not itself a body ({@code *.tmpl.*}). */
    private static boolean isDefinition(String filename) {
        return filename.endsWith(DEF_SUFFIX) && !isBody(filename);
    }

    private static boolean isBody(String filename) {
        return filename.contains(BODY_INFIX);
    }

    /** Stem of a body filename: {@code foo.tmpl.md → foo}. */
    private static String bodyStem(String filename) {
        int idx = filename.indexOf(BODY_INFIX);
        return idx < 0 ? filename : filename.substring(0, idx);
    }

    private static TemplateSource mapSource(LookupResult.Source source) {
        return switch (source) {
            case PROJECT -> TemplateSource.PROJECT;
            case VANCE -> TemplateSource.VANCE;
            case RESOURCE -> TemplateSource.RESOURCE;
        };
    }
}
