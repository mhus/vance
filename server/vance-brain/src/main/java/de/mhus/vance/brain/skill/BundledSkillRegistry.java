package de.mhus.vance.brain.skill;

import de.mhus.vance.api.skills.SkillReferenceDocLoadMode;
import de.mhus.vance.api.skills.SkillTriggerType;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Service;
import org.yaml.snakeyaml.Yaml;

/**
 * Loads bundled skills from the classpath at boot. Each bundled skill
 * lives in its own directory under {@code classpath:skills/<name>/}
 * with a {@code SKILL.md} entry-point: YAML frontmatter (between
 * {@code ---} fences) followed by markdown body. The body is the
 * skill's {@code promptExtension}.
 *
 * <p>Reference docs declared in the frontmatter ({@code referenceDocs})
 * are loaded from sibling files in the same skill directory.
 *
 * <p>Fail-fast: a missing or malformed bundled skill aborts brain
 * startup. Tenant-/project-/user-level skills live in MongoDB via
 * {@code SkillService} and are consulted by {@link SkillResolver}
 * <em>before</em> this registry.
 *
 * <p>Frontmatter schema (see {@code SKILL.md} files):
 * <pre>{@code
 * ---
 * name: code-review
 * title: Code Review
 * version: 1.0.0
 * description: Use when user asks to review PRs, diffs, or changesets
 * tags: [review, quality]
 * triggers:
 *   - type: KEYWORDS
 *     keywords: [review, diff, PR]
 *   - type: PATTERN
 *     pattern: "schau.*(diff|PR)"
 * tools:
 *   - file_read
 * referenceDocs:
 *   - file: references/checklist.md
 *     title: Review Checklist
 *     loadMode: INLINE
 * enabled: true
 * ---
 *
 * # Markdown body — this is the promptExtension
 * }</pre>
 */
@Service
@Slf4j
public class BundledSkillRegistry {

    private static final String LOCATION_PATTERN = "classpath:skills/*/SKILL.md";
    private static final String FRONTMATTER_FENCE = "---";

    private final Map<String, BundledSkill> byName = new LinkedHashMap<>();
    private final List<BundledSkill> ordered = new ArrayList<>();

    public BundledSkillRegistry() {
        load();
    }

    /** Lookup a bundled skill by name. Lowercase comparison. */
    public Optional<BundledSkill> find(String name) {
        if (name == null) return Optional.empty();
        return Optional.ofNullable(byName.get(name.toLowerCase().trim()));
    }

    /** All bundled skills in directory-name order. Defensive copy. */
    public List<BundledSkill> all() {
        return List.copyOf(ordered);
    }

    public int size() {
        return ordered.size();
    }

    private void load() {
        PathMatchingResourcePatternResolver resolver =
                new PathMatchingResourcePatternResolver(getClass().getClassLoader());
        Resource[] resources;
        try {
            resources = resolver.getResources(LOCATION_PATTERN);
        } catch (IOException e) {
            throw new IllegalStateException(
                    "Failed to scan bundled skills under '" + LOCATION_PATTERN + "'", e);
        }

        // Stable order — directory name (= skill name) ascending.
        List<Resource> sorted = new ArrayList<>(List.of(resources));
        sorted.sort(Comparator.comparing(r -> {
            String path = safeUri(r);
            return path == null ? "" : path;
        }));

        for (Resource resource : sorted) {
            BundledSkill skill = parseSkill(resource, resolver);
            String key = skill.name().toLowerCase().trim();
            if (byName.containsKey(key)) {
                throw new IllegalStateException(
                        "Duplicate bundled skill name '" + skill.name()
                                + "' (resource: " + safeUri(resource) + ")");
            }
            byName.put(key, skill);
            ordered.add(skill);
        }
        log.info("BundledSkillRegistry: loaded {} skill(s): {}",
                ordered.size(), ordered.stream().map(BundledSkill::name).toList());
    }

    private static String safeUri(Resource r) {
        try {
            return r.getURI().toString();
        } catch (IOException e) {
            return r.getDescription();
        }
    }

    private static BundledSkill parseSkill(
            Resource skillMd, PathMatchingResourcePatternResolver resolver) {
        String raw = readAsString(skillMd);
        Frontmatter fm = splitFrontmatter(raw, safeUri(skillMd));
        Map<String, Object> spec = parseYaml(fm.frontmatter, safeUri(skillMd));

        String name = requireNonBlankString(spec, "name", safeUri(skillMd));
        String title = requireNonBlankString(spec, "title", safeUri(skillMd));
        String description = requireNonBlankString(spec, "description", safeUri(skillMd));
        String version = requireNonBlankString(spec, "version", safeUri(skillMd));

        String dirName = directoryNameOf(skillMd);
        if (dirName != null && !dirName.equalsIgnoreCase(name)) {
            throw new IllegalStateException(
                    "Bundled skill directory '" + dirName
                            + "' does not match its declared name '" + name + "'");
        }

        List<BundledSkill.Trigger> triggers = parseTriggers(spec.get("triggers"), name);
        List<String> tools = stringList(spec.get("tools"), name, "tools");
        List<String> tags = stringList(spec.get("tags"), name, "tags");
        boolean enabled = !(spec.get("enabled") instanceof Boolean b) || b;

        String promptExtension = fm.body.isBlank() ? null : fm.body.strip();

        List<BundledSkill.ReferenceDoc> referenceDocs =
                parseReferenceDocs(spec.get("referenceDocs"), name, skillMd, resolver);

        // Bundled skills don't carry scripts in v1 — the directory layout
        // for shipped scripts is reserved for a later phase (see
        // specification/skills.md §13). For now an empty list keeps the
        // record uniform with Mongo-stored skills.
        List<BundledSkill.Script> scripts = List.of();

        return new BundledSkill(
                name, title, description, version,
                triggers, promptExtension, tools, referenceDocs, scripts, tags, enabled);
    }

    private static String readAsString(Resource resource) {
        try (InputStream in = resource.getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(
                    "Failed to read bundled skill resource " + safeUri(resource), e);
        }
    }

    private record Frontmatter(String frontmatter, String body) {}

    private static Frontmatter splitFrontmatter(String raw, String resourceUri) {
        String text = raw.replace("\r\n", "\n");
        if (!text.startsWith(FRONTMATTER_FENCE)) {
            throw new IllegalStateException(
                    "Bundled skill " + resourceUri
                            + " must start with a YAML frontmatter fence '---'");
        }
        int firstFenceEnd = text.indexOf('\n');
        if (firstFenceEnd < 0) {
            throw new IllegalStateException(
                    "Bundled skill " + resourceUri + " has no body after the opening fence");
        }
        int closingFence = text.indexOf("\n" + FRONTMATTER_FENCE, firstFenceEnd);
        if (closingFence < 0) {
            throw new IllegalStateException(
                    "Bundled skill " + resourceUri + " is missing a closing '---' fence");
        }
        String frontmatter = text.substring(firstFenceEnd + 1, closingFence);
        int afterClosing = closingFence + ("\n" + FRONTMATTER_FENCE).length();
        String body = afterClosing >= text.length() ? "" : text.substring(afterClosing);
        // skip optional trailing newline after closing fence
        if (body.startsWith("\n")) body = body.substring(1);
        return new Frontmatter(frontmatter, body);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> parseYaml(String text, String resourceUri) {
        try {
            Yaml yaml = new Yaml();
            Object parsed = yaml.load(text);
            if (parsed == null) {
                throw new IllegalStateException(
                        "Bundled skill " + resourceUri + ": frontmatter is empty");
            }
            if (!(parsed instanceof Map<?, ?> m)) {
                throw new IllegalStateException(
                        "Bundled skill " + resourceUri
                                + ": frontmatter must be a YAML map");
            }
            return (Map<String, Object>) m;
        } catch (RuntimeException e) {
            throw new IllegalStateException(
                    "Bundled skill " + resourceUri + ": failed to parse YAML frontmatter — "
                            + e.getMessage(), e);
        }
    }

    private static String directoryNameOf(Resource resource) {
        try {
            String uri = resource.getURI().toString();
            // …/skills/<dirName>/SKILL.md
            int suffix = uri.lastIndexOf("/SKILL.md");
            if (suffix < 0) return null;
            int dirStart = uri.lastIndexOf('/', suffix - 1);
            if (dirStart < 0) return null;
            return uri.substring(dirStart + 1, suffix);
        } catch (IOException e) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private static List<BundledSkill.Trigger> parseTriggers(Object raw, String skillName) {
        if (raw == null) return List.of();
        if (!(raw instanceof List<?> list)) {
            throw new IllegalStateException(
                    "Skill '" + skillName + "': 'triggers' must be a list");
        }
        List<BundledSkill.Trigger> out = new ArrayList<>(list.size());
        for (int i = 0; i < list.size(); i++) {
            Object item = list.get(i);
            if (!(item instanceof Map<?, ?> m)) {
                throw new IllegalStateException(
                        "Skill '" + skillName + "': triggers[" + i + "] must be a map");
            }
            Map<String, Object> spec = (Map<String, Object>) m;
            String typeRaw = requireNonBlankString(spec, "type",
                    "skill '" + skillName + "' triggers[" + i + "]");
            SkillTriggerType type;
            try {
                type = SkillTriggerType.valueOf(typeRaw.trim().toUpperCase());
            } catch (IllegalArgumentException e) {
                throw new IllegalStateException(
                        "Skill '" + skillName + "': unknown trigger type '" + typeRaw + "'");
            }
            String pattern = stringOrNull(spec.get("pattern"));
            List<String> keywords = stringList(
                    spec.get("keywords"), skillName, "triggers[" + i + "].keywords");
            switch (type) {
                case PATTERN -> {
                    if (pattern == null) {
                        throw new IllegalStateException(
                                "Skill '" + skillName + "': PATTERN trigger needs 'pattern'");
                    }
                }
                case KEYWORDS -> {
                    if (keywords.isEmpty()) {
                        throw new IllegalStateException(
                                "Skill '" + skillName + "': KEYWORDS trigger needs 'keywords'");
                    }
                }
            }
            out.add(new BundledSkill.Trigger(type, pattern, keywords));
        }
        return List.copyOf(out);
    }

    @SuppressWarnings("unchecked")
    private static List<BundledSkill.ReferenceDoc> parseReferenceDocs(
            Object raw,
            String skillName,
            Resource skillMd,
            PathMatchingResourcePatternResolver resolver) {
        if (raw == null) return List.of();
        if (!(raw instanceof List<?> list)) {
            throw new IllegalStateException(
                    "Skill '" + skillName + "': 'referenceDocs' must be a list");
        }
        List<BundledSkill.ReferenceDoc> out = new ArrayList<>(list.size());
        for (int i = 0; i < list.size(); i++) {
            Object item = list.get(i);
            if (!(item instanceof Map<?, ?> m)) {
                throw new IllegalStateException(
                        "Skill '" + skillName + "': referenceDocs[" + i + "] must be a map");
            }
            Map<String, Object> spec = (Map<String, Object>) m;
            String title = requireNonBlankString(spec, "title",
                    "skill '" + skillName + "' referenceDocs[" + i + "]");
            String filePath = requireNonBlankString(spec, "file",
                    "skill '" + skillName + "' referenceDocs[" + i + "]");
            SkillReferenceDocLoadMode loadMode = SkillReferenceDocLoadMode.INLINE;
            Object loadModeRaw = spec.get("loadMode");
            if (loadModeRaw instanceof String s && !s.isBlank()) {
                try {
                    loadMode = SkillReferenceDocLoadMode.valueOf(s.trim().toUpperCase());
                } catch (IllegalArgumentException e) {
                    throw new IllegalStateException(
                            "Skill '" + skillName + "': unknown loadMode '" + s + "'");
                }
            }
            String content = readSibling(skillMd, filePath, skillName, resolver);
            out.add(new BundledSkill.ReferenceDoc(title, content, loadMode));
        }
        return List.copyOf(out);
    }

    private static String readSibling(
            Resource skillMd,
            String relativePath,
            String skillName,
            PathMatchingResourcePatternResolver resolver) {
        try {
            Resource sibling = skillMd.createRelative(relativePath);
            if (!sibling.exists()) {
                throw new IllegalStateException(
                        "Skill '" + skillName + "': reference file '" + relativePath
                                + "' not found relative to " + safeUri(skillMd));
            }
            try (InputStream in = sibling.getInputStream()) {
                return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            throw new IllegalStateException(
                    "Skill '" + skillName + "': failed to read reference file '"
                            + relativePath + "'", e);
        }
    }

    private static String requireNonBlankString(
            Map<String, Object> spec, String key, String contextLabel) {
        Object raw = spec.get(key);
        if (!(raw instanceof String s) || s.isBlank()) {
            throw new IllegalStateException(
                    contextLabel + ": missing required string '" + key + "'");
        }
        return s.trim();
    }

    private static String stringOrNull(Object raw) {
        return raw instanceof String s && !s.isBlank() ? s : null;
    }

    private static List<String> stringList(Object raw, String skillName, String fieldName) {
        if (raw == null) return List.of();
        if (!(raw instanceof List<?> list)) {
            throw new IllegalStateException(
                    "Skill '" + skillName + "': '" + fieldName + "' must be a list");
        }
        List<String> out = new ArrayList<>(list.size());
        for (Object item : list) {
            if (!(item instanceof String s) || s.isBlank()) {
                throw new IllegalStateException(
                        "Skill '" + skillName + "': '" + fieldName
                                + "' contains a non-string or blank entry");
            }
            out.add(s);
        }
        return List.copyOf(out);
    }
}
