package de.mhus.vance.brain.kit;

import de.mhus.vance.api.kit.KitImportMode;
import de.mhus.vance.api.kit.KitInheritDto;
import de.mhus.vance.api.kit.KitOperationResultDto;
import de.mhus.vance.api.settings.SettingType;
import de.mhus.vance.shared.home.HomeBootstrapService;
import de.mhus.vance.shared.settings.SettingService;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

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

    private final KitInstaller installer;
    private final SettingService settingService;

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
            if (v == null || v.isEmpty()) v = in.defaultValue();
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
                if (in.choices().contains(v)) return v;
                throw new KitException("input '" + in.name()
                        + "': value '" + v + "' not in choices " + in.choices());
            default:
                return v;
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
        try (Stream<Path> stream = Files.walk(docsRoot)) {
            stream.filter(Files::isRegularFile).forEach(file -> {
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
            });
        } catch (IOException e) {
            throw new KitException("failed to walk " + docsRoot, e);
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
}
