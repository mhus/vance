package de.mhus.vance.brain.kit;

import de.mhus.vance.api.kit.InheritArtefactsDto;
import de.mhus.vance.api.kit.KitDescriptorDto;
import de.mhus.vance.api.kit.KitInheritDto;
import de.mhus.vance.api.kit.KitManifestDto;
import de.mhus.vance.api.kit.KitMetadataDto;
import de.mhus.vance.api.kit.KitOriginDto;
import de.mhus.vance.api.settings.SettingType;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

/**
 * Map between YAML strings and the kit DTOs. Uses SnakeYAML for both
 * directions — read via {@code Yaml.load} into untyped maps and walk
 * the structure manually so a malformed file fails with a clear
 * message rather than a stack trace.
 *
 * <p>All methods are stateless utilities; instantiation is unnecessary.
 */
public final class KitYamlMapper {

    private KitYamlMapper() {}

    // ──────────────────── kit.yaml ────────────────────

    public static KitDescriptorDto parseDescriptor(String yamlText) {
        Map<String, Object> map = loadMap(yamlText, "kit.yaml");
        String name = requireString(map, "name", "kit.yaml");
        String description = requireString(map, "description", "kit.yaml");
        String version = stringOrNull(map.get("version"));
        boolean hasEncryptedSecrets = booleanOrFalse(map.get("hasEncryptedSecrets"));
        boolean artifact = booleanOrFalse(map.get("artifact"));
        boolean installable = booleanOr(map.get("installable"), true);
        boolean sealed = booleanOrFalse(map.get("sealed"));

        // Spec: kits.md §3.2 — a kit that is neither installable nor
        // inheritable cannot be used at all. Reject the descriptor up
        // front rather than failing later with a confusing message.
        if (!installable && sealed) {
            throw new KitException(
                    "kit.yaml: 'installable: false' and 'sealed: true' together would make"
                            + " the kit unusable (no direct import, no inherit). Pick one.");
        }

        List<KitInheritDto> inherits = new ArrayList<>();
        Object inheritsRaw = map.get("inherits");
        if (inheritsRaw instanceof List<?> list) {
            for (int i = 0; i < list.size(); i++) {
                Object element = list.get(i);
                if (!(element instanceof Map<?, ?> nested)) {
                    throw new KitException("kit.yaml inherits[" + i + "] must be a map");
                }
                @SuppressWarnings("unchecked")
                Map<String, Object> e = (Map<String, Object>) nested;
                String url = requireString(e, "url", "kit.yaml inherits[" + i + "]");
                inherits.add(KitInheritDto.builder()
                        .url(url)
                        .path(stringOrNull(e.get("path")))
                        .branch(stringOrNull(e.get("branch")))
                        .commit(stringOrNull(e.get("commit")))
                        .build());
            }
        } else if (inheritsRaw != null) {
            throw new KitException("kit.yaml inherits must be a list");
        }

        return KitDescriptorDto.builder()
                .name(name)
                .description(description)
                .version(version)
                .inherits(inherits)
                .hasEncryptedSecrets(hasEncryptedSecrets)
                .artifact(artifact)
                .installable(installable)
                .sealed(sealed)
                .build();
    }

    public static String writeDescriptor(KitDescriptorDto descriptor) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("name", descriptor.getName());
        root.put("description", descriptor.getDescription());
        if (descriptor.getVersion() != null) {
            root.put("version", descriptor.getVersion());
        }
        if (descriptor.isHasEncryptedSecrets()) {
            root.put("hasEncryptedSecrets", true);
        }
        // Visibility flags only round-trip when they deviate from the
        // default — keeps the export YAML noise-free for normal kits.
        if (descriptor.isArtifact()) {
            root.put("artifact", true);
        }
        if (!descriptor.isInstallable()) {
            root.put("installable", false);
        }
        if (descriptor.isSealed()) {
            root.put("sealed", true);
        }
        if (descriptor.getInherits() != null && !descriptor.getInherits().isEmpty()) {
            List<Map<String, Object>> inherits = new ArrayList<>();
            for (KitInheritDto i : descriptor.getInherits()) {
                Map<String, Object> e = new LinkedHashMap<>();
                e.put("url", i.getUrl());
                if (i.getPath() != null) e.put("path", i.getPath());
                if (i.getBranch() != null) e.put("branch", i.getBranch());
                if (i.getCommit() != null) e.put("commit", i.getCommit());
                inherits.add(e);
            }
            root.put("inherits", inherits);
        }
        return dump(root);
    }

    // ──────────────────── kit-manifest.yaml ────────────────────

    public static KitManifestDto parseManifest(String yamlText) {
        Map<String, Object> map = loadMap(yamlText, "kit-manifest.yaml");

        Object kitRaw = map.get("kit");
        if (!(kitRaw instanceof Map<?, ?> kitMap)) {
            throw new KitException("kit-manifest.yaml must have a 'kit' map");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> kitTyped = (Map<String, Object>) kitMap;
        KitMetadataDto metadata = KitMetadataDto.builder()
                .name(requireString(kitTyped, "name", "kit-manifest.yaml kit"))
                .description(requireString(kitTyped, "description", "kit-manifest.yaml kit"))
                .version(stringOrNull(kitTyped.get("version")))
                .build();

        Object originRaw = map.get("origin");
        if (!(originRaw instanceof Map<?, ?> originMap)) {
            throw new KitException("kit-manifest.yaml must have an 'origin' map");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> originTyped = (Map<String, Object>) originMap;
        KitOriginDto origin = KitOriginDto.builder()
                .url(requireString(originTyped, "url", "kit-manifest.yaml origin"))
                .path(stringOrNull(originTyped.get("path")))
                .branch(stringOrNull(originTyped.get("branch")))
                .commit(stringOrNull(originTyped.get("commit")))
                .installedAt(parseInstant(originTyped.get("installedAt")))
                .installedBy(stringOrNull(originTyped.get("installedBy")))
                .build();

        List<KitInheritDto> inherits = new ArrayList<>();
        Object inheritsRaw = map.get("inherits");
        if (inheritsRaw instanceof List<?> list) {
            for (int i = 0; i < list.size(); i++) {
                Object element = list.get(i);
                if (!(element instanceof Map<?, ?> nested)) continue;
                @SuppressWarnings("unchecked")
                Map<String, Object> e = (Map<String, Object>) nested;
                String url = stringOrNull(e.get("url"));
                if (url == null) continue;
                inherits.add(KitInheritDto.builder()
                        .url(url)
                        .path(stringOrNull(e.get("path")))
                        .branch(stringOrNull(e.get("branch")))
                        .commit(stringOrNull(e.get("commit")))
                        .build());
            }
        }

        List<InheritArtefactsDto> inheritArtefacts = new ArrayList<>();
        Object inheritArtefactsRaw = map.get("inheritArtefacts");
        if (inheritArtefactsRaw instanceof List<?> list) {
            for (Object element : list) {
                if (!(element instanceof Map<?, ?> nested)) continue;
                @SuppressWarnings("unchecked")
                Map<String, Object> e = (Map<String, Object>) nested;
                String name = stringOrNull(e.get("name"));
                if (name == null) continue;
                inheritArtefacts.add(InheritArtefactsDto.builder()
                        .name(name)
                        .documents(stringList(e.get("documents")))
                        .settings(stringList(e.get("settings")))
                        .tools(stringList(e.get("tools")))
                        .build());
            }
        }

        return KitManifestDto.builder()
                .kit(metadata)
                .origin(origin)
                .documents(stringList(map.get("documents")))
                .settings(stringList(map.get("settings")))
                .tools(stringList(map.get("tools")))
                .inherits(inherits)
                .resolvedInherits(stringList(map.get("resolvedInherits")))
                .inheritArtefacts(inheritArtefacts)
                .hasEncryptedSecrets(booleanOrFalse(map.get("hasEncryptedSecrets")))
                .build();
    }

    public static String writeManifest(KitManifestDto manifest) {
        Map<String, Object> root = new LinkedHashMap<>();

        Map<String, Object> kit = new LinkedHashMap<>();
        kit.put("name", manifest.getKit().getName());
        kit.put("description", manifest.getKit().getDescription());
        if (manifest.getKit().getVersion() != null) {
            kit.put("version", manifest.getKit().getVersion());
        }
        root.put("kit", kit);

        Map<String, Object> origin = new LinkedHashMap<>();
        origin.put("url", manifest.getOrigin().getUrl());
        if (manifest.getOrigin().getPath() != null) origin.put("path", manifest.getOrigin().getPath());
        if (manifest.getOrigin().getBranch() != null) origin.put("branch", manifest.getOrigin().getBranch());
        if (manifest.getOrigin().getCommit() != null) origin.put("commit", manifest.getOrigin().getCommit());
        if (manifest.getOrigin().getInstalledAt() != null) {
            origin.put("installedAt", manifest.getOrigin().getInstalledAt().toString());
        }
        if (manifest.getOrigin().getInstalledBy() != null) {
            origin.put("installedBy", manifest.getOrigin().getInstalledBy());
        }
        root.put("origin", origin);

        if (manifest.getDocuments() != null && !manifest.getDocuments().isEmpty()) {
            root.put("documents", new ArrayList<>(manifest.getDocuments()));
        }
        if (manifest.getSettings() != null && !manifest.getSettings().isEmpty()) {
            root.put("settings", new ArrayList<>(manifest.getSettings()));
        }
        if (manifest.getTools() != null && !manifest.getTools().isEmpty()) {
            root.put("tools", new ArrayList<>(manifest.getTools()));
        }
        if (manifest.getInherits() != null && !manifest.getInherits().isEmpty()) {
            List<Map<String, Object>> serialized = new ArrayList<>();
            for (KitInheritDto i : manifest.getInherits()) {
                Map<String, Object> e = new LinkedHashMap<>();
                e.put("url", i.getUrl());
                if (i.getPath() != null) e.put("path", i.getPath());
                if (i.getBranch() != null) e.put("branch", i.getBranch());
                if (i.getCommit() != null) e.put("commit", i.getCommit());
                serialized.add(e);
            }
            root.put("inherits", serialized);
        }
        if (manifest.getResolvedInherits() != null && !manifest.getResolvedInherits().isEmpty()) {
            root.put("resolvedInherits", new ArrayList<>(manifest.getResolvedInherits()));
        }
        if (manifest.getInheritArtefacts() != null && !manifest.getInheritArtefacts().isEmpty()) {
            List<Map<String, Object>> serialized = new ArrayList<>();
            for (InheritArtefactsDto i : manifest.getInheritArtefacts()) {
                Map<String, Object> e = new LinkedHashMap<>();
                e.put("name", i.getName());
                if (i.getDocuments() != null && !i.getDocuments().isEmpty()) {
                    e.put("documents", new ArrayList<>(i.getDocuments()));
                }
                if (i.getSettings() != null && !i.getSettings().isEmpty()) {
                    e.put("settings", new ArrayList<>(i.getSettings()));
                }
                if (i.getTools() != null && !i.getTools().isEmpty()) {
                    e.put("tools", new ArrayList<>(i.getTools()));
                }
                serialized.add(e);
            }
            root.put("inheritArtefacts", serialized);
        }
        if (manifest.isHasEncryptedSecrets()) {
            root.put("hasEncryptedSecrets", true);
        }
        return dump(root);
    }

    // ──────────────────── settings/<key>.yaml ────────────────────

    public record ParsedSetting(SettingType type, @Nullable String value, @Nullable String description) {}

    // ──────────────────── template.yaml ────────────────────

    /**
     * Parse the {@code template.yaml} sibling of a kit's {@code kit.yaml}.
     * Tool-template kits carry this extra manifest to declare which
     * inputs the apply step needs — see
     * {@link TemplateDescriptor}.
     *
     * @param yamlText file content
     * @return parsed descriptor; never null
     * @throws KitException on schema violation (missing name, bad type, …)
     */
    @SuppressWarnings("unchecked")
    public static TemplateDescriptor parseTemplate(String yamlText) {
        Map<String, Object> map = loadMap(yamlText, "template.yaml");
        String name = requireString(map, "name", "template.yaml");
        String title = stringOrNull(map.get("title"));
        String description = stringOrNull(map.get("description"));
        String icon = stringOrNull(map.get("icon"));

        List<TemplateInput> inputs = new ArrayList<>();
        Object inputsRaw = map.get("inputs");
        if (inputsRaw instanceof List<?> list) {
            int idx = 0;
            for (Object e : list) {
                if (!(e instanceof Map<?, ?> m)) {
                    throw new KitException(
                            "template.yaml: inputs[" + idx + "] must be a map");
                }
                try {
                    inputs.add(parseInput((Map<String, Object>) m));
                } catch (IllegalArgumentException ex) {
                    throw new KitException("template.yaml: " + ex.getMessage(), ex);
                }
                idx++;
            }
        }
        // Reject duplicate input names — would silently win/lose in the
        // substitution map and is always a config bug.
        java.util.Set<String> seen = new java.util.LinkedHashSet<>();
        for (TemplateInput i : inputs) {
            if (!seen.add(i.name())) {
                throw new KitException(
                        "template.yaml: duplicate input name '" + i.name() + "'");
            }
        }

        TemplatePostInstall postInstall = null;
        Object piRaw = map.get("postInstall");
        if (piRaw instanceof Map<?, ?> piMap) {
            try {
                postInstall = parsePostInstall((Map<String, Object>) piMap);
            } catch (IllegalArgumentException ex) {
                throw new KitException("template.yaml: " + ex.getMessage(), ex);
            }
        }

        List<TemplateDerived> derived = parseDerived(map.get("derived"), inputs);
        List<TemplateDocumentOverlay> documents = parseDocumentsOverlay(
                map.get("documents"), inputs);

        return new TemplateDescriptor(name, title, description, icon,
                List.copyOf(inputs), derived, documents, postInstall);
    }

    @SuppressWarnings("unchecked")
    private static List<TemplateDerived> parseDerived(
            @Nullable Object raw, List<TemplateInput> inputs) {
        if (raw == null) return List.of();
        if (!(raw instanceof List<?> list)) {
            throw new KitException("template.yaml: 'derived' must be a list");
        }
        // Build a lookup of multi-select input names + their choice values
        // so we can validate `from` and `perChoice` keys at parse time.
        Map<String, java.util.Set<String>> multiSelectChoices = new LinkedHashMap<>();
        for (TemplateInput in : inputs) {
            if (in.type() == TemplateInputType.MULTI_SELECT) {
                multiSelectChoices.put(in.name(),
                        new java.util.LinkedHashSet<>(in.choiceValues()));
            }
        }
        List<TemplateDerived> out = new ArrayList<>();
        java.util.Set<String> seenNames = new java.util.LinkedHashSet<>();
        int idx = 0;
        for (Object el : list) {
            if (!(el instanceof Map<?, ?> mm)) {
                throw new KitException("template.yaml: derived[" + idx + "] must be a map");
            }
            Map<String, Object> mp = (Map<String, Object>) mm;
            try {
                TemplateDerived d = parseDerivedOne(mp, multiSelectChoices);
                if (!seenNames.add(d.name())) {
                    throw new IllegalArgumentException(
                            "duplicate derived name '" + d.name() + "'");
                }
                // Also forbid name collisions with inputs (would mask the input).
                for (TemplateInput in : inputs) {
                    if (in.name().equals(d.name())) {
                        throw new IllegalArgumentException(
                                "derived '" + d.name() + "' shadows input of the same name");
                    }
                }
                out.add(d);
            } catch (IllegalArgumentException e) {
                throw new KitException("template.yaml: derived[" + idx + "]: " + e.getMessage(), e);
            }
            idx++;
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static TemplateDerived parseDerivedOne(
            Map<String, Object> mp,
            Map<String, java.util.Set<String>> multiSelectChoices) {
        String name = stringOrNull(mp.get("name"));
        if (name == null) {
            throw new IllegalArgumentException("'name' is required");
        }
        TemplateDerived.Kind kind = TemplateDerived.Kind.parse(
                stringOrNull(mp.get("kind")), name);
        String from = stringOrNull(mp.get("from"));
        if (from == null) {
            throw new IllegalArgumentException(
                    "derived '" + name + "': 'from' (multi-select input name) is required");
        }
        java.util.Set<String> allowedChoices = multiSelectChoices.get(from);
        if (allowedChoices == null) {
            throw new IllegalArgumentException(
                    "derived '" + name + "': 'from' must reference a multi-select input "
                            + "(known multi-select inputs: " + multiSelectChoices.keySet() + ")");
        }
        List<String> base = stringList(mp.get("base"));
        Map<String, List<String>> perChoice = new LinkedHashMap<>();
        Object perChoiceRaw = mp.get("perChoice");
        if (perChoiceRaw != null) {
            if (!(perChoiceRaw instanceof Map<?, ?> pm)) {
                throw new IllegalArgumentException(
                        "derived '" + name + "': perChoice must be a map");
            }
            for (Map.Entry<?, ?> e : pm.entrySet()) {
                String key = e.getKey() == null ? null : e.getKey().toString();
                if (key == null) continue;
                if (!allowedChoices.contains(key)) {
                    throw new IllegalArgumentException(
                            "derived '" + name + "': perChoice key '" + key
                                    + "' is not a value of input '" + from
                                    + "' (allowed: " + allowedChoices + ")");
                }
                perChoice.put(key, stringList(e.getValue()));
            }
        }
        return new TemplateDerived(name, kind, from, base, perChoice);
    }

    @SuppressWarnings("unchecked")
    private static List<TemplateDocumentOverlay> parseDocumentsOverlay(
            @Nullable Object raw, List<TemplateInput> inputs) {
        if (raw == null) return List.of();
        if (!(raw instanceof List<?> list)) {
            throw new KitException("template.yaml: 'documents' must be a list");
        }
        // Collect all known multi-select choice values across all multi-select
        // inputs (we accept overlay-`requires` referring to any of them).
        java.util.Set<String> knownChoices = new java.util.LinkedHashSet<>();
        for (TemplateInput in : inputs) {
            if (in.type() == TemplateInputType.MULTI_SELECT) {
                knownChoices.addAll(in.choiceValues());
            }
        }
        List<TemplateDocumentOverlay> out = new ArrayList<>();
        java.util.Set<String> seenPaths = new java.util.LinkedHashSet<>();
        int idx = 0;
        for (Object el : list) {
            if (!(el instanceof Map<?, ?> mm)) {
                throw new KitException("template.yaml: documents[" + idx + "] must be a map");
            }
            Map<String, Object> mp = (Map<String, Object>) mm;
            try {
                String path = stringOrNull(mp.get("path"));
                if (path == null) {
                    throw new IllegalArgumentException("'path' is required");
                }
                if (!seenPaths.add(path)) {
                    throw new IllegalArgumentException("duplicate path '" + path + "'");
                }
                List<String> requires = stringOrList(mp.get("requires"));
                if (knownChoices.isEmpty()) {
                    throw new IllegalArgumentException(
                            "documents overlay on '" + path + "': requires a multi-select input "
                                    + "(none declared in template.yaml)");
                }
                for (String r : requires) {
                    if (!knownChoices.contains(r)) {
                        throw new IllegalArgumentException(
                                "documents '" + path + "': requires '" + r
                                        + "' is not a known multi-select choice value "
                                        + "(known: " + knownChoices + ")");
                    }
                }
                out.add(new TemplateDocumentOverlay(path, requires));
            } catch (IllegalArgumentException e) {
                throw new KitException("template.yaml: documents[" + idx + "]: " + e.getMessage(), e);
            }
            idx++;
        }
        return out;
    }

    /**
     * Accepts either a single string or a list of strings — used for
     * {@code documents.requires:} which is naturally one-or-many.
     */
    private static List<String> stringOrList(@Nullable Object raw) {
        if (raw == null) return List.of();
        if (raw instanceof List<?>) return stringList(raw);
        return List.of(raw.toString());
    }

    @SuppressWarnings("unchecked")
    private static TemplateInput parseInput(Map<String, Object> m) {
        String inputName = stringOrNull(m.get("name"));
        if (inputName == null) {
            throw new IllegalArgumentException("input: 'name' is required");
        }
        TemplateInputType type = TemplateInputType.parse(
                stringOrNull(m.get("type")), inputName);
        String label = stringOrNull(m.get("label"));
        String help = stringOrNull(m.get("help"));
        boolean required = booleanOr(m.get("required"), true);
        String defaultValue = stringOrNull(m.get("default"));
        List<TemplateChoice> choices = parseChoices(m.get("choices"), inputName);
        TemplateInputTarget target = parseTarget(m.get("target"), inputName);
        return new TemplateInput(
                inputName, type, label, help, required,
                defaultValue, choices, target);
    }

    /**
     * Accepts both the v1 flat string-list shape ({@code [a, b, c]}) and
     * the v2 richer map-list shape
     * ({@code [{value: a, label: A, default: true}, …]}).
     *
     * <p>For multi-select, per-choice defaults can only be expressed via
     * the map form — the flat form is allowed for compatibility but
     * defaults to {@code default=false} per choice. The string form is
     * deliberately permitted so existing v1 single-select templates keep
     * parsing unchanged.
     */
    @SuppressWarnings("unchecked")
    private static List<TemplateChoice> parseChoices(@Nullable Object raw, String inputName) {
        if (raw == null) return List.of();
        if (!(raw instanceof List<?> list)) {
            throw new IllegalArgumentException(
                    "input '" + inputName + "': choices must be a list");
        }
        List<TemplateChoice> out = new ArrayList<>(list.size());
        int idx = 0;
        for (Object el : list) {
            if (el == null) {
                throw new IllegalArgumentException(
                        "input '" + inputName + "': choices[" + idx + "] is null");
            }
            if (el instanceof Map<?, ?> mm) {
                Map<String, Object> mp = (Map<String, Object>) mm;
                String value = stringOrNull(mp.get("value"));
                if (value == null) {
                    throw new IllegalArgumentException(
                            "input '" + inputName + "': choices[" + idx
                                    + "]: 'value' is required");
                }
                String label = stringOrNull(mp.get("label"));
                boolean dflt = booleanOr(mp.get("default"), false);
                out.add(new TemplateChoice(value, label, dflt));
            } else {
                out.add(new TemplateChoice(el.toString(), null, false));
            }
            idx++;
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static TemplateInputTarget parseTarget(@Nullable Object raw, String inputName) {
        if (raw == null) return TemplateInputTarget.documentInline();
        if (!(raw instanceof Map<?, ?> m)) {
            throw new IllegalArgumentException(
                    "input '" + inputName + "': target must be a map");
        }
        Map<String, Object> tm = (Map<String, Object>) m;
        String kindRaw = stringOrNull(tm.get("kind"));
        if (kindRaw == null || "document-inline".equalsIgnoreCase(kindRaw)
                || "inline".equalsIgnoreCase(kindRaw)) {
            return TemplateInputTarget.documentInline();
        }
        if (!"setting".equalsIgnoreCase(kindRaw)) {
            throw new IllegalArgumentException(
                    "input '" + inputName + "': target.kind must be 'setting' or 'document-inline'");
        }
        TemplateInputTarget.Scope scope = TemplateInputTarget.Scope.parse(
                stringOrNull(tm.get("scope")), inputName);
        String project = stringOrNull(tm.get("project"));
        String key = stringOrNull(tm.get("key"));
        if (key == null) {
            throw new IllegalArgumentException(
                    "input '" + inputName + "': target.key is required for kind=setting");
        }
        if (scope == TemplateInputTarget.Scope.PROJECT && project == null) {
            // project=null means "apply to the project the kit is being
            // applied to" — explicit choice, validated at apply time.
        }
        return new TemplateInputTarget(
                TemplateInputTarget.Kind.SETTING, scope, project, key);
    }

    private static TemplatePostInstall parsePostInstall(Map<String, Object> m) {
        TemplatePostInstall.Kind kind = TemplatePostInstall.Kind.parse(
                stringOrNull(m.get("kind")));
        String provider = stringOrNull(m.get("provider"));
        String message = stringOrNull(m.get("message"));
        if (kind == TemplatePostInstall.Kind.OAUTH_CONNECT && provider == null) {
            throw new IllegalArgumentException(
                    "postInstall (oauth-connect): 'provider' is required");
        }
        return new TemplatePostInstall(kind, provider, message);
    }

    // ──────────────────── settings/*.yaml ────────────────────

    public static ParsedSetting parseSetting(String yamlText, String filename) {
        Map<String, Object> map = loadMap(yamlText, filename);
        String typeRaw = requireString(map, "type", filename);
        SettingType type;
        try {
            type = SettingType.valueOf(typeRaw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new KitException(filename + ": unknown setting type '" + typeRaw + "'");
        }
        Object valueRaw = map.get("value");
        String value = valueRaw == null ? null : valueRaw.toString();
        return new ParsedSetting(type, value, stringOrNull(map.get("description")));
    }

    public static String writeSetting(ParsedSetting setting) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("type", setting.type().name());
        if (setting.value() != null) root.put("value", setting.value());
        if (setting.description() != null) root.put("description", setting.description());
        return dump(root);
    }

    // ──────────────────── helpers ────────────────────

    @SuppressWarnings("unchecked")
    private static Map<String, Object> loadMap(String yamlText, String label) {
        Yaml yaml = new Yaml();
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

    private static String dump(Map<String, Object> map) {
        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        options.setIndent(2);
        options.setPrettyFlow(true);
        return new Yaml(options).dump(map);
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

    private static List<String> stringList(@Nullable Object v) {
        if (v == null) return new ArrayList<>();
        if (!(v instanceof List<?> list)) {
            throw new KitException("expected a list, got " + v.getClass().getSimpleName());
        }
        List<String> result = new ArrayList<>();
        for (Object e : list) {
            if (e == null) continue;
            String s = e.toString().trim();
            if (!s.isEmpty()) result.add(s);
        }
        return result;
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
