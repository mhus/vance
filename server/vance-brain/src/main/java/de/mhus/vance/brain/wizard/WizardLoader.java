package de.mhus.vance.brain.wizard;

import de.mhus.vance.api.form.FormChoiceDto;
import de.mhus.vance.api.form.FormFieldDto;
import de.mhus.vance.brain.prompt.PromptTemplateException;
import de.mhus.vance.brain.prompt.PromptTemplateRenderer;
import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.shared.document.DocumentStatus;
import de.mhus.vance.shared.document.LookupResult;
import de.mhus.vance.shared.home.HomeBootstrapService;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.yaml.snakeyaml.Yaml;

/**
 * Loads and parses wizard YAMLs through the four-tier cascade
 * {@code project → _user_<userId> → _vance → classpath:vance-defaults/wizards/}.
 *
 * <p>The {@link DocumentService} only knows the standard three-tier
 * cascade (project / vance / resource); the {@code _user_<userId>}
 * layer is wizard-specific and assembled here by explicit lookup
 * against the user's namespace project.
 *
 * <p>Parse failures on individual list entries are logged on WARN and
 * skipped — a single broken wizard must not hide the rest from the
 * Web-UI's tab. Single-name {@link #load(String, String, String, String)}
 * throws so the controller can surface a clean 400/500.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class WizardLoader {

    /** Path prefix used for wizard documents in any cascade tier. */
    public static final String WIZARD_PATH_PREFIX = "_vance/wizards/";

    /** File suffix kept on the document path; the wizard name itself does not carry it. */
    public static final String WIZARD_PATH_SUFFIX = ".yaml";

    /** Username-namespace project pattern (see CLAUDE.md memory: "user_projects_no_home_pod"). */
    public static final String USER_PROJECT_PREFIX = "_user_";

    private final DocumentService documentService;
    private final PromptTemplateRenderer templateRenderer;

    /**
     * Resolve {@code name} against the four-tier cascade. Returns
     * empty when no layer carries the wizard.
     */
    public Optional<ResolvedWizard> load(
            String tenantId,
            @Nullable String projectId,
            @Nullable String userId,
            String name) {
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }
        String path = pathFor(name);
        String normalizedName = name.toLowerCase().trim();

        // 1. Project layer (skipped when projectId is the tenant project itself).
        if (projectId != null
                && !projectId.isBlank()
                && !HomeBootstrapService.TENANT_PROJECT_NAME.equals(projectId)) {
            Optional<ResolvedWizard> projectHit =
                    tryParseLayer(tenantId, projectId, path, normalizedName, WizardSource.PROJECT);
            if (projectHit.isPresent()) return projectHit;
        }

        // 2. User layer.
        if (userId != null && !userId.isBlank()) {
            String userProject = USER_PROJECT_PREFIX + userId;
            Optional<ResolvedWizard> userHit =
                    tryParseLayer(tenantId, userProject, path, normalizedName, WizardSource.USER);
            if (userHit.isPresent()) return userHit;
        }

        // 3-4. _tenant + classpath fallback via the standard cascade
        // (passing TENANT_PROJECT_NAME forces the PROJECT layer to be skipped).
        Optional<LookupResult> hit = documentService.lookupCascade(
                tenantId, HomeBootstrapService.TENANT_PROJECT_NAME, path);
        if (hit.isEmpty()) {
            return Optional.empty();
        }
        LookupResult result = hit.get();
        try {
            return Optional.of(parse(normalizedName, result.content(), mapVanceOrResource(result.source())));
        } catch (RuntimeException e) {
            throw new WizardParseException(
                    "Failed to parse wizard '" + name + "' from "
                            + result.source() + " at '" + result.path() + "': " + e.getMessage(), e);
        }
    }

    /**
     * Merged listing across the cascade. Innermost layer wins per
     * wizard name. Parse failures on individual entries are skipped
     * with a WARN log.
     */
    public List<ResolvedWizard> listAll(
            String tenantId,
            @Nullable String projectId,
            @Nullable String userId) {
        Map<String, ResolvedWizard> byName = new LinkedHashMap<>();

        // Outer-to-inner so later puts override earlier ones.

        // 1. _tenant + classpath, via standard cascade.
        Map<String, LookupResult> vanceTier = documentService.listByPrefixCascade(
                tenantId, HomeBootstrapService.TENANT_PROJECT_NAME, WIZARD_PATH_PREFIX);
        applyTier(byName, vanceTier, /*onlyProjectSource=*/false, /*projectSourceLabel=*/null);

        // 2. _user_<userId>: only the PROJECT-source hits (those that actually
        // live in the user namespace, not the _tenant + classpath fall-throughs
        // that listByPrefixCascade also returns).
        if (userId != null && !userId.isBlank()) {
            Map<String, LookupResult> userTier = documentService.listByPrefixCascade(
                    tenantId, USER_PROJECT_PREFIX + userId, WIZARD_PATH_PREFIX);
            applyTier(byName, userTier, /*onlyProjectSource=*/true, WizardSource.USER);
        }

        // 3. Project: same idea — PROJECT-source hits only.
        if (projectId != null
                && !projectId.isBlank()
                && !HomeBootstrapService.TENANT_PROJECT_NAME.equals(projectId)) {
            Map<String, LookupResult> projectTier = documentService.listByPrefixCascade(
                    tenantId, projectId, WIZARD_PATH_PREFIX);
            applyTier(byName, projectTier, /*onlyProjectSource=*/true, WizardSource.PROJECT);
        }

        // Apply the availableIn glob filter against the requested projectId.
        // Listing without a project context is treated as the tenant project.
        String effectiveProjectId =
                (projectId == null || projectId.isBlank())
                        ? HomeBootstrapService.TENANT_PROJECT_NAME
                        : projectId;
        List<ResolvedWizard> filtered = new ArrayList<>(byName.size());
        for (ResolvedWizard w : byName.values()) {
            if (isAvailableIn(w.availableIn(), effectiveProjectId)) {
                filtered.add(w);
            }
        }
        return filtered;
    }

    /**
     * Decides whether a wizard's {@code availableIn} patterns match the
     * given project id. Patterns starting with {@code "!"} are excludes;
     * a single exclude match removes the wizard. If the list contains
     * only excludes, a missing positive match is treated as implicit
     * {@code "*"} (include-all).
     *
     * <p>Package-private to allow unit testing without going through
     * {@link DocumentService} stubs.
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

    /**
     * Tiny glob matcher: {@code *} matches any run of non-{@code /}
     * characters; all other characters match literally. Used by
     * {@link #isAvailableIn}.
     */
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

    private void applyTier(
            Map<String, ResolvedWizard> acc,
            Map<String, LookupResult> tier,
            boolean onlyProjectSource,
            @Nullable WizardSource projectSourceLabel) {
        for (Map.Entry<String, LookupResult> e : tier.entrySet()) {
            String path = e.getKey();
            LookupResult result = e.getValue();
            if (onlyProjectSource && result.source() != LookupResult.Source.PROJECT) {
                continue;
            }
            String name = nameFromPath(path);
            if (name == null) continue;
            WizardSource source = onlyProjectSource
                    ? projectSourceLabel
                    : mapVanceOrResource(result.source());
            if (source == null) continue;
            try {
                acc.put(name, parse(name, result.content(), source));
            } catch (RuntimeException ex) {
                log.warn("WizardLoader: skipping malformed wizard path='{}' source={}: {}",
                        path, result.source(), ex.getMessage());
            }
        }
    }

    private Optional<ResolvedWizard> tryParseLayer(
            String tenantId,
            String projectId,
            String path,
            String name,
            WizardSource source) {
        Optional<DocumentDocument> doc = documentService.findByPath(tenantId, projectId, path);
        if (doc.isEmpty() || doc.get().getStatus() != DocumentStatus.ACTIVE) {
            return Optional.empty();
        }
        String content = documentService.readContent(doc.get());
        try {
            return Optional.of(parse(name, content, source));
        } catch (RuntimeException e) {
            throw new WizardParseException(
                    "Failed to parse wizard '" + name + "' from " + source + " at '" + path + "': "
                            + e.getMessage(), e);
        }
    }

    private static String pathFor(String name) {
        return WIZARD_PATH_PREFIX + name.toLowerCase().trim() + WIZARD_PATH_SUFFIX;
    }

    private static @Nullable String nameFromPath(String path) {
        if (!path.startsWith(WIZARD_PATH_PREFIX)) return null;
        if (!path.endsWith(WIZARD_PATH_SUFFIX)) return null;
        String stem = path.substring(
                WIZARD_PATH_PREFIX.length(),
                path.length() - WIZARD_PATH_SUFFIX.length());
        return stem.isBlank() ? null : stem;
    }

    private static WizardSource mapVanceOrResource(LookupResult.Source source) {
        return switch (source) {
            // PROJECT shouldn't occur when the caller forces _tenant as the project arg;
            // be defensive anyway and treat it as the tenant layer.
            case PROJECT, VANCE -> WizardSource.VANCE;
            case RESOURCE -> WizardSource.RESOURCE;
        };
    }

    @SuppressWarnings("unchecked")
    private ResolvedWizard parse(String name, String yamlContent, WizardSource source) {
        Yaml yaml = new Yaml();
        Object parsed = yaml.load(yamlContent);
        if (!(parsed instanceof Map<?, ?> rawMap)) {
            throw new IllegalStateException("wizard YAML must have a top-level map");
        }
        Map<String, Object> spec = (Map<String, Object>) rawMap;

        Map<String, String> title = requiredLocalizedText(spec.get("title"), "title");
        Map<String, String> description = requiredLocalizedText(spec.get("description"), "description");
        String icon = optionalString(spec.get("icon"));
        String category = optionalString(spec.get("category"));

        List<FormFieldDto> fields = parseFields(spec.get("fields"), "fields");
        if (fields.isEmpty()) {
            throw new IllegalStateException("wizard must declare at least one field");
        }

        String promptTemplate = optionalString(spec.get("promptTemplate"));
        if (promptTemplate == null) {
            throw new IllegalStateException("missing required field 'promptTemplate'");
        }
        compileTemplate(promptTemplate, "promptTemplate");

        String validatorPrompt = optionalString(spec.get("validatorPrompt"));
        if (validatorPrompt != null) {
            compileTemplate(validatorPrompt, "validatorPrompt");
        }

        List<WizardFollowUp> followUps = parseFollowUps(spec.get("suggestedFollowUps"));
        List<String> availableIn = parseAvailableIn(spec.get("availableIn"));

        return new ResolvedWizard(
                name, title, description, icon, category, fields,
                promptTemplate, validatorPrompt, followUps, availableIn, source);
    }

    /**
     * Parses the {@code availableIn} top-level field — list of project-id
     * glob patterns used by {@link #listAll} to filter visibility. Empty
     * or missing → {@code ["*"]} (visible everywhere).
     *
     * <p>Each entry is normalized as a non-blank string; a {@code "!"} prefix
     * is preserved (it's the exclude marker — see spec §2a). Leading and
     * trailing whitespace is trimmed.
     */
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
                throw new IllegalStateException(
                        "'availableIn[" + i + "]' must be a string");
            }
            String trimmed = s.trim();
            if (trimmed.isEmpty()) {
                throw new IllegalStateException("'availableIn[" + i + "]' is blank");
            }
            out.add(trimmed);
        }
        return List.copyOf(out);
    }

    @SuppressWarnings("unchecked")
    private List<WizardFollowUp> parseFollowUps(@Nullable Object raw) {
        if (raw == null) return List.of();
        if (!(raw instanceof List<?> list)) {
            throw new IllegalStateException("'suggestedFollowUps' must be a list");
        }
        List<WizardFollowUp> out = new ArrayList<>(list.size());
        for (int i = 0; i < list.size(); i++) {
            Object entry = list.get(i);
            if (!(entry instanceof Map<?, ?> entryMap)) {
                throw new IllegalStateException(
                        "'suggestedFollowUps[" + i + "]' must be a map");
            }
            Map<String, Object> m = (Map<String, Object>) entryMap;
            String wizard = optionalString(m.get("wizard"));
            if (wizard == null) {
                throw new IllegalStateException(
                        "'suggestedFollowUps[" + i + "].wizard' is required");
            }
            Map<String, String> label = requiredLocalizedText(
                    m.get("label"), "suggestedFollowUps[" + i + "].label");
            Map<String, String> prefill = parsePrefill(
                    m.get("prefill"), "suggestedFollowUps[" + i + "].prefill");
            // Each prefill value is itself a small Pebble template — compile to surface
            // errors at load time, the same way the main promptTemplate is checked.
            for (Map.Entry<String, String> p : prefill.entrySet()) {
                compileTemplate(p.getValue(),
                        "suggestedFollowUps[" + i + "].prefill." + p.getKey());
            }
            String condition = optionalString(m.get("condition"));
            if (condition != null) {
                // Wrap so Pebble parses the expression in a statement context.
                compileTemplate("{% if " + condition + " %}1{% endif %}",
                        "suggestedFollowUps[" + i + "].condition");
            }
            out.add(new WizardFollowUp(wizard, label, prefill, condition));
        }
        return List.copyOf(out);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> parsePrefill(@Nullable Object raw, String path) {
        if (raw == null) return Map.of();
        if (!(raw instanceof Map<?, ?> rawMap)) {
            throw new IllegalStateException("'" + path + "' must be a map");
        }
        Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : rawMap.entrySet()) {
            String key = String.valueOf(e.getKey());
            if (key.isBlank()) {
                throw new IllegalStateException("'" + path + "' contains a blank key");
            }
            Object v = e.getValue();
            if (v == null) continue;
            out.put(key, String.valueOf(v));
        }
        return Map.copyOf(out);
    }

    @SuppressWarnings("unchecked")
    private List<FormFieldDto> parseFields(@Nullable Object raw, String parentPath) {
        if (raw == null) return List.of();
        if (!(raw instanceof List<?> list)) {
            throw new IllegalStateException("'" + parentPath + "' must be a list");
        }
        List<FormFieldDto> out = new ArrayList<>(list.size());
        for (int i = 0; i < list.size(); i++) {
            Object entry = list.get(i);
            if (!(entry instanceof Map<?, ?> entryMap)) {
                throw new IllegalStateException(
                        "'" + parentPath + "[" + i + "]' must be a map");
            }
            out.add(parseField((Map<String, Object>) entryMap, parentPath + "[" + i + "]"));
        }
        return List.copyOf(out);
    }

    @SuppressWarnings("unchecked")
    private FormFieldDto parseField(Map<String, Object> raw, String path) {
        String name = optionalString(raw.get("name"));
        if (name == null) {
            throw new IllegalStateException("'" + path + ".name' is required");
        }
        String type = optionalString(raw.get("type"));
        if (type == null) {
            throw new IllegalStateException("'" + path + ".type' is required");
        }
        Map<String, String> label = requiredLocalizedText(raw.get("label"), path + ".label");
        Map<String, String> help = optionalLocalizedText(raw.get("help"), path + ".help");
        boolean required = raw.get("required") instanceof Boolean b && b;
        String defaultValue = raw.get("defaultValue") == null
                ? null
                : String.valueOf(raw.get("defaultValue"));
        List<FormChoiceDto> choices = parseChoices(raw.get("choices"), path + ".choices");
        Integer rows = optionalInt(raw.get("rows"), path + ".rows");
        Integer integerMin = optionalInt(raw.get("integerMin"), path + ".integerMin");
        Integer integerMax = optionalInt(raw.get("integerMax"), path + ".integerMax");
        Integer min = optionalInt(raw.get("min"), path + ".min");
        Integer max = optionalInt(raw.get("max"), path + ".max");
        List<FormFieldDto> item = null;
        if ("repeat".equals(type)) {
            item = parseFields(raw.get("item"), path + ".item");
            if (item.isEmpty()) {
                throw new IllegalStateException("'" + path + ".item' must declare at least one nested field");
            }
        }
        return FormFieldDto.builder()
                .name(name)
                .type(type)
                .label(label)
                .help(help)
                .required(required)
                .defaultValue(defaultValue)
                .choices(choices)
                .rows(rows)
                .integerMin(integerMin)
                .integerMax(integerMax)
                .min(min)
                .max(max)
                .item(item)
                .build();
    }

    @SuppressWarnings("unchecked")
    private static List<FormChoiceDto> parseChoices(@Nullable Object raw, String path) {
        if (raw == null) return List.of();
        if (!(raw instanceof List<?> list)) {
            throw new IllegalStateException("'" + path + "' must be a list");
        }
        List<FormChoiceDto> out = new ArrayList<>(list.size());
        for (int i = 0; i < list.size(); i++) {
            Object entry = list.get(i);
            if (entry instanceof String s) {
                if (s.isBlank()) {
                    throw new IllegalStateException("'" + path + "[" + i + "]' is blank");
                }
                out.add(FormChoiceDto.builder().value(s).build());
                continue;
            }
            if (!(entry instanceof Map<?, ?> entryMap)) {
                throw new IllegalStateException(
                        "'" + path + "[" + i + "]' must be a string or a map");
            }
            Map<String, Object> m = (Map<String, Object>) entryMap;
            String value = optionalString(m.get("value"));
            if (value == null) {
                throw new IllegalStateException("'" + path + "[" + i + "].value' is required");
            }
            Map<String, String> label = optionalLocalizedText(
                    m.get("label"), path + "[" + i + "].label");
            boolean def = (m.get("default") instanceof Boolean b && b)
                    || (m.get("defaultSelected") instanceof Boolean b2 && b2);
            out.add(FormChoiceDto.builder()
                    .value(value)
                    .label(label)
                    .defaultSelected(def)
                    .build());
        }
        return List.copyOf(out);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> requiredLocalizedText(@Nullable Object raw, String path) {
        Map<String, String> resolved = optionalLocalizedText(raw, path);
        if (resolved == null || resolved.isEmpty()) {
            throw new IllegalStateException("'" + path + "' is required");
        }
        return resolved;
    }

    /**
     * Parses a {@code Map<lang, text>} entry. Also accepts a plain
     * string as a shortcut — it becomes a single-entry map under the
     * literal key {@code "en"} so downstream localization resolves it
     * via the universal-fallback rule in
     * {@link de.mhus.vance.shared.form.LocalizedTexts#resolve}.
     */
    @SuppressWarnings("unchecked")
    private static @Nullable Map<String, String> optionalLocalizedText(@Nullable Object raw, String path) {
        if (raw == null) return null;
        if (raw instanceof String s) {
            if (s.isBlank()) return null;
            return Map.of("en", s);
        }
        if (!(raw instanceof Map<?, ?> rawMap)) {
            throw new IllegalStateException(
                    "'" + path + "' must be a string or a map of language → text");
        }
        Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : rawMap.entrySet()) {
            String lang = String.valueOf(e.getKey());
            if (lang.isBlank()) {
                throw new IllegalStateException("'" + path + "' contains a blank language key");
            }
            Object v = e.getValue();
            if (v == null) continue;
            if (!(v instanceof String s)) {
                throw new IllegalStateException(
                        "'" + path + "." + lang + "' must be a string");
            }
            if (s.isBlank()) continue;
            out.put(lang, s);
        }
        return Map.copyOf(out);
    }

    private static @Nullable String optionalString(@Nullable Object raw) {
        return raw instanceof String s && !s.isBlank() ? s : null;
    }

    private static @Nullable Integer optionalInt(@Nullable Object raw, String path) {
        if (raw == null) return null;
        if (raw instanceof Integer i) return i;
        if (raw instanceof Long l) return l.intValue();
        if (raw instanceof Number n) return n.intValue();
        if (raw instanceof String s) {
            try {
                return Integer.parseInt(s.trim());
            } catch (NumberFormatException e) {
                throw new IllegalStateException("'" + path + "' is not an integer: " + s);
            }
        }
        throw new IllegalStateException("'" + path + "' must be an integer");
    }

    private void compileTemplate(String template, String fieldName) {
        try {
            templateRenderer.compile(template);
        } catch (PromptTemplateException e) {
            throw new IllegalStateException(
                    "'" + fieldName + "' is not a valid Pebble template: " + e.getMessage(), e);
        }
    }
}
