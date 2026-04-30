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

    // ──────────────────── tools/<name>.tool.yaml ────────────────────

    /**
     * Returns the raw map view of a tool YAML — the {@code KitInstaller}
     * lifts the well-known fields ({@code name}, {@code type}, ...) onto
     * a {@code ServerToolDocument} and treats the rest as
     * {@code parameters}.
     */
    public static Map<String, Object> parseToolMap(String yamlText, String filename) {
        return loadMap(yamlText, filename);
    }

    @SuppressWarnings("unchecked")
    public static String writeToolMap(Map<String, Object> map) {
        return dump(new LinkedHashMap<>(map));
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
        if (v == null) return false;
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
