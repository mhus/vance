package de.mhus.vance.brain.settingform;

import de.mhus.vance.api.form.BindsToDto;
import de.mhus.vance.api.form.FormChoiceDto;
import de.mhus.vance.api.form.FormFieldDto;
import de.mhus.vance.api.settings.SettingType;
import de.mhus.vance.brain.prompt.PromptTemplateException;
import de.mhus.vance.brain.prompt.PromptTemplateRenderer;
import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.shared.document.DocumentStatus;
import de.mhus.vance.shared.document.LookupResult;
import de.mhus.vance.shared.home.HomeBootstrapService;
import de.mhus.vance.shared.settings.SettingService;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.yaml.snakeyaml.Yaml;

/**
 * Loads and parses Setting Form YAMLs through the four-tier cascade
 * {@code project → _user_<userId> → _vance → classpath:vance-defaults/setting_forms/}.
 *
 * <p>Pebble templates (the {@code value} entries and the
 * {@code showIf}/{@code writeIf} expressions) are compile-checked at
 * load time — malformed forms are rejected (single load) or skipped
 * with a WARN (listing).
 *
 * <p>Form-field parsing mirrors {@code WizardLoader} but extends the
 * standard {@link FormFieldDto} with the Setting-Form-only
 * {@code bindsTo} / {@code showIf} / {@code writeIf} extras. Wizard
 * YAMLs that lack these fields parse fine through here too — the
 * extras are simply {@code null}.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SettingFormLoader {

    /** Path prefix used for setting-form documents in any cascade tier. */
    public static final String SETTING_FORM_PATH_PREFIX = "setting_forms/";

    /** File suffix kept on the document path; the form name itself does not carry it. */
    public static final String SETTING_FORM_PATH_SUFFIX = ".yaml";

    /** Username-namespace project pattern (mirrors {@code WizardLoader}). */
    public static final String USER_PROJECT_PREFIX = "_user_";

    /** Default top-level {@code defaultScope} when the YAML omits it. */
    public static final String DEFAULT_SCOPE_FALLBACK = SettingService.SCOPE_PROJECT;

    /** Allowed values for {@code defaultScope} / per-binding {@code scope}. */
    public static final Set<String> ALLOWED_SCOPES = Set.of(
            SettingService.SCOPE_PROJECT,
            SettingService.SCOPE_USER,
            SettingService.SCOPE_TENANT);

    /** Form-field types that may carry a {@code bindsTo}. Other types must route through {@code settings:}. */
    private static final Set<String> SCALAR_BINDABLE_TYPES = Set.of(
            "string", "textarea", "password", "integer", "boolean", "select");

    private final DocumentService documentService;
    private final PromptTemplateRenderer templateRenderer;

    /**
     * Resolves {@code name} against the four-tier cascade. Returns
     * empty when no layer carries the form.
     *
     * @throws SettingFormParseException when a hit exists but its YAML is malformed.
     */
    public Optional<ResolvedSettingForm> load(
            String tenantId,
            @Nullable String projectId,
            @Nullable String userId,
            String name) {
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }
        String path = pathFor(name);
        String normalized = name.toLowerCase(Locale.ROOT).trim();

        if (projectId != null
                && !projectId.isBlank()
                && !HomeBootstrapService.TENANT_PROJECT_NAME.equals(projectId)) {
            Optional<ResolvedSettingForm> hit = tryParseLayer(
                    tenantId, projectId, path, normalized, SettingFormSource.PROJECT);
            if (hit.isPresent()) return hit;
        }

        if (userId != null && !userId.isBlank()) {
            Optional<ResolvedSettingForm> hit = tryParseLayer(
                    tenantId, USER_PROJECT_PREFIX + userId, path, normalized, SettingFormSource.USER);
            if (hit.isPresent()) return hit;
        }

        Optional<LookupResult> tenantOrResource = documentService.lookupCascade(
                tenantId, HomeBootstrapService.TENANT_PROJECT_NAME, path);
        if (tenantOrResource.isEmpty()) {
            return Optional.empty();
        }
        LookupResult result = tenantOrResource.get();
        try {
            return Optional.of(parse(normalized, result.content(), mapVanceOrResource(result.source())));
        } catch (SettingFormParseException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new SettingFormParseException(
                    "Failed to parse setting form '" + name + "' from "
                            + result.source() + " at '" + result.path() + "': " + e.getMessage(), e);
        }
    }

    /**
     * Merged listing across the cascade with innermost-layer-wins
     * dedup and {@code availableIn} filtering against
     * {@code effectiveProjectId}. Parse failures on individual entries
     * are logged WARN and skipped.
     */
    public List<ResolvedSettingForm> listAll(
            String tenantId,
            @Nullable String projectId,
            @Nullable String userId) {
        Map<String, ResolvedSettingForm> byName = new LinkedHashMap<>();

        Map<String, LookupResult> vanceTier = documentService.listByPrefixCascade(
                tenantId, HomeBootstrapService.TENANT_PROJECT_NAME, SETTING_FORM_PATH_PREFIX);
        applyTier(byName, vanceTier, /*onlyProjectSource=*/false, /*projectSourceLabel=*/null);

        if (userId != null && !userId.isBlank()) {
            Map<String, LookupResult> userTier = documentService.listByPrefixCascade(
                    tenantId, USER_PROJECT_PREFIX + userId, SETTING_FORM_PATH_PREFIX);
            applyTier(byName, userTier, /*onlyProjectSource=*/true, SettingFormSource.USER);
        }

        if (projectId != null
                && !projectId.isBlank()
                && !HomeBootstrapService.TENANT_PROJECT_NAME.equals(projectId)) {
            Map<String, LookupResult> projectTier = documentService.listByPrefixCascade(
                    tenantId, projectId, SETTING_FORM_PATH_PREFIX);
            applyTier(byName, projectTier, /*onlyProjectSource=*/true, SettingFormSource.PROJECT);
        }

        String effectiveProjectId =
                (projectId == null || projectId.isBlank())
                        ? HomeBootstrapService.TENANT_PROJECT_NAME
                        : projectId;
        List<ResolvedSettingForm> filtered = new ArrayList<>(byName.size());
        for (ResolvedSettingForm f : byName.values()) {
            if (isAvailableIn(f.availableIn(), effectiveProjectId)) {
                filtered.add(f);
            }
        }
        return filtered;
    }

    /**
     * Glob filter for {@code availableIn} — identical semantics to the
     * Wizard subsystem (see spec §2a). Package-private so unit tests
     * can drive it directly.
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

    private void applyTier(
            Map<String, ResolvedSettingForm> acc,
            Map<String, LookupResult> tier,
            boolean onlyProjectSource,
            @Nullable SettingFormSource projectSourceLabel) {
        for (Map.Entry<String, LookupResult> e : tier.entrySet()) {
            String path = e.getKey();
            LookupResult result = e.getValue();
            if (onlyProjectSource && result.source() != LookupResult.Source.PROJECT) {
                continue;
            }
            String name = nameFromPath(path);
            if (name == null) continue;
            SettingFormSource source = onlyProjectSource
                    ? projectSourceLabel
                    : mapVanceOrResource(result.source());
            if (source == null) continue;
            try {
                acc.put(name, parse(name, result.content(), source));
            } catch (RuntimeException ex) {
                log.warn("SettingFormLoader: skipping malformed form path='{}' source={}: {}",
                        path, result.source(), ex.getMessage());
            }
        }
    }

    private Optional<ResolvedSettingForm> tryParseLayer(
            String tenantId,
            String projectId,
            String path,
            String name,
            SettingFormSource source) {
        Optional<DocumentDocument> doc = documentService.findByPath(tenantId, projectId, path);
        if (doc.isEmpty() || doc.get().getStatus() != DocumentStatus.ACTIVE) {
            return Optional.empty();
        }
        String content = documentService.readContent(doc.get());
        try {
            return Optional.of(parse(name, content, source));
        } catch (RuntimeException e) {
            throw new SettingFormParseException(
                    "Failed to parse setting form '" + name + "' from " + source + " at '"
                            + path + "': " + e.getMessage(), e);
        }
    }

    private static String pathFor(String name) {
        return SETTING_FORM_PATH_PREFIX + name.toLowerCase(Locale.ROOT).trim()
                + SETTING_FORM_PATH_SUFFIX;
    }

    private static @Nullable String nameFromPath(String path) {
        if (!path.startsWith(SETTING_FORM_PATH_PREFIX)) return null;
        if (!path.endsWith(SETTING_FORM_PATH_SUFFIX)) return null;
        String stem = path.substring(
                SETTING_FORM_PATH_PREFIX.length(),
                path.length() - SETTING_FORM_PATH_SUFFIX.length());
        return stem.isBlank() ? null : stem;
    }

    private static SettingFormSource mapVanceOrResource(LookupResult.Source source) {
        return switch (source) {
            case PROJECT, VANCE -> SettingFormSource.VANCE;
            case RESOURCE -> SettingFormSource.RESOURCE;
        };
    }

    // ──────────────────── Parsing ────────────────────

    @SuppressWarnings("unchecked")
    ResolvedSettingForm parse(String name, String yamlContent, SettingFormSource source) {
        Yaml yaml = new Yaml();
        Object parsed = yaml.load(yamlContent);
        if (!(parsed instanceof Map<?, ?> rawMap)) {
            throw new IllegalStateException("setting-form YAML must have a top-level map");
        }
        Map<String, Object> spec = (Map<String, Object>) rawMap;

        Map<String, String> title = requiredLocalizedText(spec.get("title"), "title");
        Map<String, String> description = requiredLocalizedText(spec.get("description"), "description");
        String icon = optionalString(spec.get("icon"));
        String category = optionalString(spec.get("category"));

        String defaultScope = parseScope(
                optionalString(spec.get("defaultScope")), "defaultScope", DEFAULT_SCOPE_FALLBACK);

        List<FormFieldDto> fields = parseFields(spec.get("fields"), "fields");
        // Note: an empty fields list is allowed when the form drives purely computed settings,
        // but at least one field OR one computed setting must exist.
        List<ResolvedComputedSetting> computed = parseComputedSettings(
                spec.get("settings"), "settings");
        if (fields.isEmpty() && computed.isEmpty()) {
            throw new IllegalStateException(
                    "setting form must declare at least one field or one computed setting");
        }

        boolean clearable = !(spec.get("clearable") instanceof Boolean b) || b;
        List<String> availableIn = parseAvailableIn(spec.get("availableIn"));

        // Validate showIf/writeIf templates on every field.
        for (FormFieldDto f : fields) {
            compileExpression(f.getShowIf(), "fields[" + f.getName() + "].showIf");
            compileExpression(f.getWriteIf(), "fields[" + f.getName() + "].writeIf");
        }

        // Conflict-Detection — see spec §11. Two unconditional entries
        // referencing the same (key, scope) tuple are a definition error;
        // reject the form so it never enters the listing. Entries gated by
        // writeIf are allowed to share a key (mutually-exclusive presets /
        // overrides — see quota-preset.yaml for the canonical pattern).
        detectUnconditionalKeyConflicts(fields, computed, defaultScope);

        return new ResolvedSettingForm(
                name, title, description, icon, category,
                defaultScope, fields, computed, clearable, availableIn, source);
    }

    private static void detectUnconditionalKeyConflicts(
            List<FormFieldDto> fields,
            List<ResolvedComputedSetting> computed,
            String defaultScope) {
        Map<String, String> seenUnconditional = new LinkedHashMap<>();
        for (FormFieldDto f : fields) {
            BindsToDto b = f.getBindsTo();
            if (b == null) continue;
            // Fields with writeIf or showIf are conditional — skip the strict
            // unconditional-collision check, the planner will surface a real
            // double-WRITE collision at runtime if values overlap.
            if (f.getWriteIf() != null || f.getShowIf() != null) continue;
            String scope = b.getScope() == null ? defaultScope : b.getScope();
            String tuple = b.getKey() + "@" + scope;
            String prior = seenUnconditional.put(tuple, "fields[" + f.getName() + "]");
            if (prior != null) {
                throw new IllegalStateException(
                        "duplicate unconditional setting target '" + tuple
                                + "' — already declared at " + prior);
            }
        }
        for (int i = 0; i < computed.size(); i++) {
            ResolvedComputedSetting c = computed.get(i);
            if (c.writeIfExpression() != null) continue;
            String scope = c.scope() == null ? defaultScope : c.scope();
            String tuple = c.key() + "@" + scope;
            String prior = seenUnconditional.put(tuple, "settings[" + i + "]");
            if (prior != null) {
                throw new IllegalStateException(
                        "duplicate unconditional setting target '" + tuple
                                + "' — already declared at " + prior);
            }
        }
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
                throw new IllegalStateException(
                        "'" + path + ".item' must declare at least one nested field");
            }
        }

        // Setting-Form extensions.
        BindsToDto bindsTo = parseBindsTo(raw.get("bindsTo"), path + ".bindsTo", type);
        String showIf = optionalString(raw.get("showIf"));
        String writeIf = optionalString(raw.get("writeIf"));
        String choicesFrom = optionalString(raw.get("choicesFrom"));
        if (choicesFrom != null) {
            if (!"ai-models".equals(choicesFrom)) {
                throw new IllegalStateException(
                        "'" + path + ".choicesFrom' unknown source: '" + choicesFrom
                                + "' (known: 'ai-models')");
            }
            if (!choices.isEmpty()) {
                throw new IllegalStateException(
                        "'" + path + "' declares both 'choices' and 'choicesFrom' — pick one");
            }
            if (!"select".equals(type) && !"multi_select".equals(type)) {
                throw new IllegalStateException(
                        "'" + path + ".choicesFrom' only applies to select / multi_select, "
                                + "got '" + type + "'");
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
                .bindsTo(bindsTo)
                .showIf(showIf)
                .writeIf(writeIf)
                .choicesFrom(choicesFrom)
                .build();
    }

    @SuppressWarnings("unchecked")
    private @Nullable BindsToDto parseBindsTo(@Nullable Object raw, String path, String fieldType) {
        if (raw == null) return null;
        if (!(raw instanceof Map<?, ?> rawMap)) {
            throw new IllegalStateException("'" + path + "' must be a map { key, scope?, settingType? }");
        }
        Map<String, Object> m = (Map<String, Object>) rawMap;
        String key = optionalString(m.get("key"));
        if (key == null) {
            throw new IllegalStateException("'" + path + ".key' is required");
        }
        String scope = parseOptionalScope(optionalString(m.get("scope")), path + ".scope");
        String settingType = optionalString(m.get("settingType"));
        if (settingType != null) {
            // Validate against the enum so typos fail fast.
            parseSettingTypeName(settingType, path + ".settingType");
        }
        if (!SCALAR_BINDABLE_TYPES.contains(fieldType)) {
            throw new IllegalStateException(
                    "'" + path + "' is not allowed on field-type '" + fieldType
                            + "'. Use a `settings:` entry for derived values.");
        }
        return BindsToDto.builder()
                .key(key)
                .scope(scope)
                .settingType(settingType)
                .build();
    }

    @SuppressWarnings("unchecked")
    private List<ResolvedComputedSetting> parseComputedSettings(
            @Nullable Object raw, String parentPath) {
        if (raw == null) return List.of();
        if (!(raw instanceof List<?> list)) {
            throw new IllegalStateException("'" + parentPath + "' must be a list");
        }
        List<ResolvedComputedSetting> out = new ArrayList<>(list.size());
        for (int i = 0; i < list.size(); i++) {
            Object entry = list.get(i);
            if (!(entry instanceof Map<?, ?> entryMap)) {
                throw new IllegalStateException(
                        "'" + parentPath + "[" + i + "]' must be a map");
            }
            out.add(parseComputedSetting(
                    (Map<String, Object>) entryMap, parentPath + "[" + i + "]"));
        }
        return List.copyOf(out);
    }

    private ResolvedComputedSetting parseComputedSetting(Map<String, Object> raw, String path) {
        String key = optionalString(raw.get("key"));
        if (key == null) {
            throw new IllegalStateException("'" + path + ".key' is required");
        }
        String scope = parseOptionalScope(optionalString(raw.get("scope")), path + ".scope");
        String settingTypeName = optionalString(raw.get("settingType"));
        SettingType settingType = settingTypeName == null
                ? SettingType.STRING
                : parseSettingTypeName(settingTypeName, path + ".settingType");
        String valueTemplate = optionalString(raw.get("value"));
        if (valueTemplate == null) {
            throw new IllegalStateException("'" + path + ".value' is required");
        }
        compileTemplate(valueTemplate, path + ".value");
        String writeIfExpression = optionalString(raw.get("writeIf"));
        compileExpression(writeIfExpression, path + ".writeIf");
        Map<String, String> description = optionalLocalizedText(
                raw.get("description"), path + ".description");
        return new ResolvedComputedSetting(
                key, scope, settingType, valueTemplate, writeIfExpression, description);
    }

    private static SettingType parseSettingTypeName(String raw, String path) {
        try {
            return SettingType.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(
                    "'" + path + "' must be one of STRING/INT/LONG/DOUBLE/BOOLEAN/PASSWORD, got: " + raw);
        }
    }

    private static String parseScope(@Nullable String raw, String path, String fallback) {
        if (raw == null) return fallback;
        String normalized = raw.trim().toLowerCase(Locale.ROOT);
        if (!ALLOWED_SCOPES.contains(normalized)) {
            throw new IllegalStateException(
                    "'" + path + "' must be one of " + ALLOWED_SCOPES + ", got: " + raw);
        }
        return normalized;
    }

    private static @Nullable String parseOptionalScope(@Nullable String raw, String path) {
        if (raw == null) return null;
        String normalized = raw.trim().toLowerCase(Locale.ROOT);
        if (!ALLOWED_SCOPES.contains(normalized)) {
            throw new IllegalStateException(
                    "'" + path + "' must be one of " + ALLOWED_SCOPES + ", got: " + raw);
        }
        return normalized;
    }

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

    private static Map<String, String> requiredLocalizedText(@Nullable Object raw, String path) {
        Map<String, String> resolved = optionalLocalizedText(raw, path);
        if (resolved == null || resolved.isEmpty()) {
            throw new IllegalStateException("'" + path + "' is required");
        }
        return resolved;
    }

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

    private void compileTemplate(@Nullable String template, String fieldName) {
        if (template == null) return;
        try {
            templateRenderer.compile(template);
        } catch (PromptTemplateException e) {
            throw new IllegalStateException(
                    "'" + fieldName + "' is not a valid Pebble template: " + e.getMessage(), e);
        }
    }

    /**
     * Compiles a Pebble boolean expression by wrapping it in a no-op
     * {@code {% if ... %}1{% endif %}} statement — mirrors the
     * FollowUpRenderer technique. Lets template authors write the
     * condition body without the surrounding tags.
     */
    private void compileExpression(@Nullable String expression, String fieldName) {
        if (expression == null) return;
        try {
            templateRenderer.compile("{% if " + expression + " %}1{% endif %}");
        } catch (PromptTemplateException e) {
            throw new IllegalStateException(
                    "'" + fieldName + "' is not a valid Pebble expression: " + e.getMessage(), e);
        }
    }
}
