package de.mhus.vance.brain.kit;

import de.mhus.vance.api.kit.KitImportMode;
import de.mhus.vance.api.kit.KitInheritDto;
import de.mhus.vance.api.kit.KitOperationResultDto;
import de.mhus.vance.api.settings.SettingType;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.shared.home.HomeBootstrapService;
import de.mhus.vance.shared.settings.SettingService;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

/**
 * Apply path for a "template" kit — one with a {@code template.yaml}
 * sibling of {@code kit.yaml} declaring an input schema. The applier
 * runs over the resolved build-tree on disk:
 *
 * <ol>
 *   <li>Read {@code template.yaml}, validate the supplied input values
 *       against the schema (required, type, choices). Reject unknowns.</li>
 *   <li>Rewrite every file under {@code documents/} on disk to replace
 *       {@code &#123;&#123;var:fieldName&#125;&#125;} with the supplied value —
 *       except for inputs whose {@code target.kind == setting}.</li>
 *   <li>Hand the (now-substituted) build tree to {@link KitInstaller}
 *       for the normal apply.</li>
 *   <li>After KitInstaller persists documents, write the
 *       {@code target=setting} inputs via {@link SettingService}
 *       (PASSWORD-typed inputs are stored encrypted).</li>
 * </ol>
 *
 * <p>Designed to be invocable on top of any kit that already works
 * with {@link KitImportMode#APPLY} — kits without a {@code template.yaml}
 * are just normal one-way kits and bypass this class entirely.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class TemplateApplier {

    /** Pattern for {@code {{var:<name>}}} placeholders. */
    private static final Pattern VAR_REF =
            Pattern.compile("\\{\\{\\s*var\\s*:\\s*([A-Za-z][A-Za-z0-9_-]*)\\s*\\}\\}");

    /** File name of the template manifest in a kit-root. */
    public static final String TEMPLATE_FILENAME = "template.yaml";

    /**
     * Document path inside the apply-target project where the post-apply
     * audit blob lands. One file per template name; re-apply overwrites.
     */
    public static final String APPLIED_PATH_PREFIX = "_vance/tool-templates/";
    public static final String APPLIED_PATH_SUFFIX = ".applied.yaml";

    private final KitInstaller installer;
    private final SettingService settingService;
    private final DocumentService documentService;

    /**
     * Applies {@code resolved} as a template-driven kit. The build tree
     * is rewritten in place — {@code resolved.cleanup(...)} (called by
     * {@link KitService}) removes the workspace afterwards, so the
     * in-place edit is safe.
     *
     * @param tenantId  target tenant
     * @param projectId target project (must already exist)
     * @param source    origin descriptor (just passed through to installer)
     * @param resolved  cloned + inherits-resolved kit on disk
     * @param inputs    user-supplied values keyed by {@link TemplateInput#name()}
     * @param actor     who triggered this (audit)
     * @return result that piggy-backs on the installer's result; plus a
     *         {@link TemplatePostInstall} pointer for the caller to surface
     */
    public ApplyResult applyTemplate(
            String tenantId,
            String projectId,
            KitInheritDto source,
            KitResolver.ResolvedKit resolved,
            Map<String, String> inputs,
            @Nullable String actor) {

        Path buildRoot = resolved.buildRoot();
        Path templatePath = buildRoot.resolve(TEMPLATE_FILENAME);
        if (!Files.isRegularFile(templatePath)) {
            throw new KitException("kit is not a template — no "
                    + TEMPLATE_FILENAME + " at " + buildRoot);
        }

        TemplateDescriptor descriptor;
        try {
            descriptor = KitYamlMapper.parseTemplate(Files.readString(templatePath));
        } catch (IOException e) {
            throw new KitException("failed to read " + templatePath, e);
        }

        Map<String, String> sanitised = validateInputs(descriptor, inputs);

        // Partition inputs by target.
        Map<String, TemplateInput> byName = new LinkedHashMap<>();
        for (TemplateInput in : descriptor.inputs()) byName.put(in.name(), in);

        Map<String, String> docVars = new LinkedHashMap<>();
        Map<String, SettingTarget> settingWrites = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : sanitised.entrySet()) {
            TemplateInput in = byName.get(e.getKey());
            if (in.target().kind() == TemplateInputTarget.Kind.SETTING) {
                settingWrites.put(in.name(), new SettingTarget(in, e.getValue()));
            } else {
                docVars.put(in.name(), e.getValue());
            }
        }

        // Evaluate derived variables (e.g. oauth_scopes = union over the selected
        // features) and feed them into the substitution map. Each derived shadows
        // the input scope intentionally — they cannot collide with input names
        // because parseDerived already enforces that.
        Map<String, List<String>> derivedLists = evaluateDerived(
                descriptor.derived(), sanitised, byName);
        for (Map.Entry<String, List<String>> e : derivedLists.entrySet()) {
            docVars.put(e.getKey(), renderJsonStringArray(e.getValue()));
        }

        // Apply documents-overlay before substitution: drop files whose
        // `requires` features are not in the user's selection.
        applyDocumentsOverlay(buildRoot, descriptor.documents(), sanitised, byName);

        // Substitute placeholders in every document on disk.
        substituteBuildTree(buildRoot, docVars);

        // Delegate the rest to KitInstaller — same code path as a regular
        // one-way kit (APPLY mode, no manifest, no vault).
        KitOperationResultDto installerResult = installer.apply(
                tenantId, projectId, source, resolved,
                KitImportMode.APPLY,
                /*prune*/ false,
                /*keepPasswords*/ false,
                /*vaultPassword*/ null,
                actor);

        // Persist setting-targeted inputs after the documents land —
        // settings reference templates that may live in those documents
        // (e.g. cascade lookups expect the project doc tree to exist).
        for (SettingTarget st : settingWrites.values()) {
            persistSetting(tenantId, projectId, st);
        }

        // Persist the post-apply audit blob — pre-fill cache for the
        // Web-UI wizard, plus a record of what was applied. Secrets are
        // structurally excluded (see buildAppliedState).
        writeAppliedState(
                tenantId, projectId, descriptor, sanitised, byName,
                derivedLists, installerResult.getSourceCommit(), actor);

        log.info("TemplateApplier: applied template '{}' tenant='{}' project='{}' inputs={} settings={}",
                descriptor.name(), tenantId, projectId,
                docVars.size(), settingWrites.size());

        return new ApplyResult(
                installerResult, descriptor.postInstall(), descriptor.name());
    }

    /** Outcome of a template apply — the installer's report plus the post-install hook. */
    public record ApplyResult(
            KitOperationResultDto installer,
            @Nullable TemplatePostInstall postInstall,
            String templateName) {}

    // ──────────────────── Internals ────────────────────

    /**
     * Validates that {@code inputs} provides every required field with
     * a parseable value. Returns the cleaned map (defaults filled in,
     * booleans normalised to "true"/"false", unknown keys dropped with
     * a warning).
     */
    private static Map<String, String> validateInputs(
            TemplateDescriptor descriptor, Map<String, String> inputs) {
        Map<String, String> raw = inputs == null ? Map.of() : new HashMap<>(inputs);
        Map<String, String> out = new LinkedHashMap<>();

        for (TemplateInput in : descriptor.inputs()) {
            String v = raw.remove(in.name());
            if (v == null || v.isEmpty()) v = computeDefault(in);
            if (v == null || v.isEmpty()) {
                if (in.required()) {
                    throw new KitException("template '" + descriptor.name()
                            + "': required input '" + in.name() + "' is missing");
                }
                continue;
            }
            out.put(in.name(), validateValue(in, v));
        }
        if (!raw.isEmpty()) {
            log.warn("TemplateApplier: dropped unknown input(s) for template '{}': {}",
                    descriptor.name(), raw.keySet());
        }
        return out;
    }

    /**
     * Computes the effective default for an input when the caller didn't
     * supply a value. For multi-select that's the JSON-encoded list of
     * choices marked {@code default: true}; for everything else it's the
     * scalar {@code defaultValue} on the input.
     */
    private static @Nullable String computeDefault(TemplateInput in) {
        if (in.type() == TemplateInputType.MULTI_SELECT) {
            List<String> defaults = new java.util.ArrayList<>();
            for (TemplateChoice c : in.choices()) {
                if (c.defaultSelected()) defaults.add(c.value());
            }
            return defaults.isEmpty() ? null : renderJsonStringArray(defaults);
        }
        return in.defaultValue();
    }

    private static String validateValue(TemplateInput in, String v) {
        switch (in.type()) {
            case STRING, PASSWORD:
                return v;
            case BOOLEAN:
                if ("true".equalsIgnoreCase(v.trim()) || "false".equalsIgnoreCase(v.trim())) {
                    return v.trim().toLowerCase();
                }
                throw new KitException("input '" + in.name()
                        + "': boolean expected, got '" + v + "'");
            case INTEGER:
                try {
                    Integer.parseInt(v.trim());
                    return v.trim();
                } catch (NumberFormatException e) {
                    throw new KitException("input '" + in.name()
                            + "': integer expected, got '" + v + "'");
                }
            case SELECT:
                if (in.choiceValues().contains(v)) return v;
                throw new KitException("input '" + in.name()
                        + "': value '" + v + "' not in choices " + in.choiceValues());
            case MULTI_SELECT:
                return validateMultiSelect(in, v);
            default:
                return v;
        }
    }

    /**
     * Multi-select values arrive as a JSON-array string (e.g.
     * {@code ["jira","confluence"]}). Parsed, deduplicated, validated
     * against the declared choices, and re-serialised in declaration
     * order so the rendered substitution is deterministic. Empty arrays
     * are allowed when the input is not required.
     */
    private static String validateMultiSelect(TemplateInput in, String v) {
        List<String> selected = parseJsonStringArray(in.name(), v);
        List<String> allowed = in.choiceValues();
        java.util.Set<String> seen = new java.util.LinkedHashSet<>();
        for (String sel : selected) {
            if (!allowed.contains(sel)) {
                throw new KitException("input '" + in.name()
                        + "': value '" + sel + "' not in choices " + allowed);
            }
            seen.add(sel);
        }
        // Re-order in declaration order so deterministic substitution.
        List<String> ordered = new java.util.ArrayList<>();
        for (String a : allowed) if (seen.contains(a)) ordered.add(a);
        if (in.required() && ordered.isEmpty()) {
            throw new KitException("input '" + in.name()
                    + "': at least one choice must be selected");
        }
        return renderJsonStringArray(ordered);
    }

    private static List<String> parseJsonStringArray(String inputName, String raw) {
        String s = raw == null ? "" : raw.trim();
        if (s.isEmpty()) return List.of();
        // Tolerant of two shapes: real JSON array, or a comma-separated bare
        // string (anus / chat-agent can hand off either).
        if (s.startsWith("[")) {
            try {
                com.fasterxml.jackson.databind.JsonNode node =
                        JSON.readTree(s);
                if (!node.isArray()) {
                    throw new KitException("input '" + inputName
                            + "': multi-select value must be a JSON array, got " + node.getNodeType());
                }
                List<String> out = new java.util.ArrayList<>();
                for (com.fasterxml.jackson.databind.JsonNode el : node) {
                    if (!el.isTextual()) {
                        throw new KitException("input '" + inputName
                                + "': multi-select elements must be strings, got " + el.getNodeType());
                    }
                    String t = el.asText();
                    if (!t.isBlank()) out.add(t);
                }
                return out;
            } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                throw new KitException("input '" + inputName
                        + "': multi-select value is not valid JSON: " + e.getMessage(), e);
            }
        }
        // Fallback: comma-separated.
        List<String> out = new java.util.ArrayList<>();
        for (String part : s.split(",")) {
            String t = part.trim();
            if (!t.isEmpty()) out.add(t);
        }
        return out;
    }

    static String renderJsonStringArray(List<String> values) {
        try {
            return JSON.writeValueAsString(values);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new KitException("failed to render JSON array: " + e.getMessage(), e);
        }
    }

    /** Shared ObjectMapper for multi-select / derived-union rendering. */
    private static final com.fasterxml.jackson.databind.ObjectMapper JSON =
            new com.fasterxml.jackson.databind.ObjectMapper();

    /**
     * Computes each derived variable as a list of values, in deterministic
     * order: {@code base} first, then {@code perChoice[selectedValue]} for
     * each value in the {@code from}-input's selection (in declaration
     * order of that input's choices). Duplicates are removed.
     */
    static Map<String, List<String>> evaluateDerived(
            List<TemplateDerived> derivedList,
            Map<String, String> sanitisedInputs,
            Map<String, TemplateInput> inputsByName) {
        Map<String, List<String>> out = new LinkedHashMap<>();
        for (TemplateDerived d : derivedList) {
            TemplateInput driver = inputsByName.get(d.from());
            // Parse the multi-select value back to a list (it's a JSON array string).
            List<String> selected = List.of();
            String raw = sanitisedInputs.get(d.from());
            if (raw != null && !raw.isEmpty()) {
                selected = parseJsonStringArray(d.from(), raw);
            }
            java.util.LinkedHashSet<String> acc = new java.util.LinkedHashSet<>(d.base());
            // Iterate in driver's choice declaration order so the union
            // ordering is independent of how the user clicked the boxes.
            java.util.Set<String> selectedSet = new java.util.HashSet<>(selected);
            for (TemplateChoice c : driver.choices()) {
                if (!selectedSet.contains(c.value())) continue;
                List<String> contrib = d.perChoice().get(c.value());
                if (contrib != null) acc.addAll(contrib);
            }
            out.put(d.name(), new java.util.ArrayList<>(acc));
        }
        return out;
    }

    /**
     * Deletes documents from the build tree whose {@code requires:} list
     * has no overlap with the user's multi-select selection. Documents
     * not listed in {@code overlay} are kept (the overlay is opt-in, not
     * an exhaustive whitelist).
     */
    private static void applyDocumentsOverlay(
            Path buildRoot,
            List<TemplateDocumentOverlay> overlay,
            Map<String, String> sanitisedInputs,
            Map<String, TemplateInput> inputsByName) {
        if (overlay.isEmpty()) return;
        // Collect the union of all selections across all multi-select inputs.
        java.util.Set<String> activeFeatures = new java.util.LinkedHashSet<>();
        for (TemplateInput in : inputsByName.values()) {
            if (in.type() != TemplateInputType.MULTI_SELECT) continue;
            String raw = sanitisedInputs.get(in.name());
            if (raw == null || raw.isEmpty()) continue;
            activeFeatures.addAll(parseJsonStringArray(in.name(), raw));
        }
        Path docsRoot = buildRoot.resolve(KitInstaller.DOCUMENTS_DIR);
        for (TemplateDocumentOverlay e : overlay) {
            boolean keep = false;
            for (String req : e.requires()) {
                if (activeFeatures.contains(req)) { keep = true; break; }
            }
            Path file = docsRoot.resolve(e.path()).normalize();
            // Guard against path-traversal attempts pointing outside docsRoot.
            if (!file.startsWith(docsRoot)) {
                throw new KitException(
                        "documents overlay '" + e.path() + "' escapes documents/ root");
            }
            if (!keep) {
                try {
                    if (Files.isRegularFile(file)) {
                        Files.delete(file);
                        log.debug("TemplateApplier: filtered out document '{}' (requires={}, active={})",
                                e.path(), e.requires(), activeFeatures);
                    } else if (Files.exists(file)) {
                        log.warn("TemplateApplier: documents-overlay path '{}' is not a regular file — skipped",
                                e.path());
                    } else {
                        // Document referenced in overlay but missing on disk —
                        // parse-time validation didn't catch it because the path
                        // is resolved against the build tree, not the source. Be
                        // strict: a stale overlay entry is always a kit bug.
                        log.warn("TemplateApplier: documents-overlay references missing path '{}'",
                                e.path());
                    }
                } catch (IOException ioe) {
                    throw new KitException("failed to delete filtered document " + file, ioe);
                }
            }
        }
    }

    /**
     * Walks {@code buildRoot/documents/} and replaces {@code {{var:X}}}
     * with the supplied value in every file. Files outside
     * {@code documents/} (kit.yaml, template.yaml) are left alone.
     */
    private static void substituteBuildTree(Path buildRoot, Map<String, String> vars) {
        Path docsRoot = buildRoot.resolve(KitInstaller.DOCUMENTS_DIR);
        if (!Files.isDirectory(docsRoot)) return;
        for (Path file : KitTree.walkNoSymlinks(docsRoot)) {
            if (!Files.isRegularFile(file)) continue;
            try {
                String content = Files.readString(file, StandardCharsets.UTF_8);
                String substituted = substitute(content, vars,
                        buildRoot.relativize(file).toString());
                if (!substituted.equals(content)) {
                    Files.writeString(file, substituted, StandardCharsets.UTF_8);
                }
            } catch (IOException e) {
                throw new KitException("failed to rewrite " + file, e);
            }
        }
    }

    /**
     * Replaces every {@code {{var:<name>}}} with {@code vars.get(name)}.
     * Unknown variable references trigger a {@link KitException} —
     * silent passthrough would land literal placeholder strings in a
     * persisted document, which is always a bug.
     */
    static String substitute(String content, Map<String, String> vars, String fileLabel) {
        Matcher m = VAR_REF.matcher(content);
        StringBuilder out = new StringBuilder();
        int last = 0;
        while (m.find()) {
            String key = m.group(1);
            if (!vars.containsKey(key)) {
                throw new KitException(fileLabel + ": references unknown variable '{{var:"
                        + key + "}}' (not declared in template.yaml inputs, or input has "
                        + "target=setting and is therefore not available for inline substitution)");
            }
            out.append(content, last, m.start());
            out.append(vars.get(key));
            last = m.end();
        }
        out.append(content, last, content.length());
        return out.toString();
    }

    private void persistSetting(String tenantId, String applyProject, SettingTarget st) {
        String project = resolveProjectFor(st.input.target(), applyProject);
        String key = st.input.target().key();
        if (st.input.type() == TemplateInputType.PASSWORD) {
            settingService.setEncryptedPassword(
                    tenantId, SettingService.SCOPE_PROJECT, project, key, st.value);
        } else {
            settingService.set(
                    tenantId, SettingService.SCOPE_PROJECT, project, key,
                    st.value, SettingType.STRING, null);
        }
    }

    private static String resolveProjectFor(TemplateInputTarget target, String applyProject) {
        TemplateInputTarget.Scope scope = target.scope();
        if (scope == null) return applyProject;
        return switch (scope) {
            case TENANT -> HomeBootstrapService.TENANT_PROJECT_NAME;
            case USER ->
                // USER scope requires an explicit user-project (e.g. "_user_<login>") —
                // the template author either passes it via target.project or this is
                // not supported at apply time. For now we accept the same field
                // semantics: if target.project is set, use it; else fall back to
                // the apply-project (which would be the _user_<X> project for the
                // user-hub case).
                    target.project() != null ? target.project() : applyProject;
            case PROJECT ->
                    target.project() != null ? target.project() : applyProject;
        };
    }

    private record SettingTarget(TemplateInput input, String value) {}

    /**
     * Returns the project-document path under which the applied-state
     * YAML for {@code templateName} is stored.
     */
    public static String appliedPathFor(String templateName) {
        return APPLIED_PATH_PREFIX + templateName + APPLIED_PATH_SUFFIX;
    }

    /**
     * Build the audit blob written after each successful apply. <strong>Hard
     * invariant:</strong> inputs of {@link TemplateInputType#PASSWORD} are
     * never included — see {@code TemplateAppliedStateTest}. Multi-select
     * values are decoded to YAML lists, integers to integers, booleans to
     * booleans; everything else lands as a string. Derived variables and
     * the active feature set are mirrored for read-back convenience.
     */
    static Map<String, Object> buildAppliedState(
            TemplateDescriptor descriptor,
            Map<String, String> sanitisedInputs,
            Map<String, TemplateInput> inputsByName,
            Map<String, List<String>> derivedLists,
            @Nullable String sourceCommit,
            @Nullable String actor,
            Instant appliedAt) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("template", descriptor.name());
        out.put("appliedAt", appliedAt.toString());
        if (actor != null && !actor.isBlank()) out.put("appliedBy", actor);
        if (sourceCommit != null && !sourceCommit.isBlank()) {
            out.put("sourceCommit", sourceCommit);
        }

        Map<String, Object> inputs = new LinkedHashMap<>();
        List<String> features = null;
        for (TemplateInput in : descriptor.inputs()) {
            // STRUCTURAL EXCLUSION — see TemplateAppliedStateTest. Loosening
            // this rule is a security-relevant change and requires a careful
            // re-review of every template author's expectations.
            if (in.type() == TemplateInputType.PASSWORD) continue;
            String val = sanitisedInputs.get(in.name());
            if (val == null) continue;
            switch (in.type()) {
                case MULTI_SELECT -> {
                    List<String> list = parseJsonStringArray(in.name(), val);
                    inputs.put(in.name(), list);
                    if (features == null) features = list;
                }
                case INTEGER -> {
                    try {
                        inputs.put(in.name(), Long.parseLong(val.trim()));
                    } catch (NumberFormatException nfe) {
                        inputs.put(in.name(), val);
                    }
                }
                case BOOLEAN -> inputs.put(in.name(), "true".equalsIgnoreCase(val.trim()));
                default -> inputs.put(in.name(), val);
            }
        }
        out.put("inputs", inputs);
        if (features != null) out.put("features", features);
        if (!derivedLists.isEmpty()) {
            // Preserve a stable LinkedHashMap so YAML diffs read cleanly
            // across re-applies.
            Map<String, List<String>> derivedCopy = new LinkedHashMap<>(derivedLists);
            out.put("derived", derivedCopy);
        }
        return out;
    }

    private void writeAppliedState(
            String tenantId,
            String projectId,
            TemplateDescriptor descriptor,
            Map<String, String> sanitisedInputs,
            Map<String, TemplateInput> inputsByName,
            Map<String, List<String>> derivedLists,
            @Nullable String sourceCommit,
            @Nullable String actor) {
        Map<String, Object> state = buildAppliedState(
                descriptor, sanitisedInputs, inputsByName, derivedLists,
                sourceCommit, actor, Instant.now());
        String yaml = renderYaml(state);
        String path = appliedPathFor(descriptor.name());
        String title = "Applied state — " + descriptor.name();
        List<String> tags = List.of("tool-template", "applied");
        try {
            documentService.upsertText(tenantId, projectId, path, title, tags, yaml, actor);
        } catch (RuntimeException e) {
            // Best-effort: a failure here must not roll back the apply
            // (documents + settings are already persisted). Log and move
            // on — the wizard can still work, just without pre-fill.
            log.warn("TemplateApplier: failed to write applied-state for '{}' in tenant='{}' project='{}': {}",
                    descriptor.name(), tenantId, projectId, e.getMessage());
        }
    }

    private static String renderYaml(Map<String, Object> map) {
        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        options.setIndent(2);
        options.setPrettyFlow(true);
        return new Yaml(options).dump(map);
    }

    /**
     * Reads the post-apply audit blob for {@code templateName} on
     * {@code (tenantId, projectId)}. Returns {@link java.util.Optional#empty}
     * when the template has never been applied to this project.
     *
     * <p>Parses the YAML back into a strongly-typed
     * {@link de.mhus.vance.api.kit.ToolTemplateAppliedStateDto}. Any
     * unknown YAML fields are ignored — forward-compatible with future
     * additions to {@link #buildAppliedState}.
     */
    @SuppressWarnings("unchecked")
    public java.util.Optional<de.mhus.vance.api.kit.ToolTemplateAppliedStateDto> loadApplied(
            String tenantId, String projectId, String templateName) {
        String path = appliedPathFor(templateName);
        java.util.Optional<de.mhus.vance.shared.document.DocumentDocument> doc =
                documentService.findByPath(tenantId, projectId, path);
        if (doc.isEmpty()) return java.util.Optional.empty();
        String text;
        try (java.io.InputStream stream = documentService.loadContent(doc.get())) {
            text = new String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (java.io.IOException e) {
            throw new KitException("failed to read applied state " + path, e);
        }
        Object parsed = new Yaml().load(text);
        if (!(parsed instanceof Map<?, ?> rawMap)) {
            throw new KitException("applied state " + path + " is not a YAML map");
        }
        Map<String, Object> map = (Map<String, Object>) rawMap;
        de.mhus.vance.api.kit.ToolTemplateAppliedStateDto.ToolTemplateAppliedStateDtoBuilder b =
                de.mhus.vance.api.kit.ToolTemplateAppliedStateDto.builder();
        b.template(strOrNull(map.get("template")));
        b.appliedAt(strOrNull(map.get("appliedAt")));
        b.appliedBy(strOrNull(map.get("appliedBy")));
        b.sourceCommit(strOrNull(map.get("sourceCommit")));
        Object inputsRaw = map.get("inputs");
        b.inputs(inputsRaw instanceof Map<?, ?> im
                ? new LinkedHashMap<>((Map<String, Object>) im)
                : new LinkedHashMap<>());
        Object featuresRaw = map.get("features");
        b.features(featuresRaw instanceof List<?> fl ? toStringList(fl) : List.of());
        Object derivedRaw = map.get("derived");
        Map<String, Object> derivedMap = new LinkedHashMap<>();
        if (derivedRaw instanceof Map<?, ?> dm) {
            for (Map.Entry<?, ?> e : dm.entrySet()) {
                if (e.getKey() == null || !(e.getValue() instanceof List<?> vl)) continue;
                derivedMap.put(e.getKey().toString(), toStringList(vl));
            }
        }
        b.derived(derivedMap);
        return java.util.Optional.of(b.build());
    }

    private static @Nullable String strOrNull(@Nullable Object v) {
        return v == null ? null : v.toString();
    }

    private static List<String> toStringList(List<?> raw) {
        List<String> out = new ArrayList<>(raw.size());
        for (Object o : raw) if (o != null) out.add(o.toString());
        return out;
    }
}
