package de.mhus.vance.brain.skill;

import de.mhus.vance.api.skills.SkillReferenceDocLoadMode;
import de.mhus.vance.api.skills.SkillScope;
import de.mhus.vance.api.skills.SkillTriggerType;
import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.shared.document.LookupResult;
import de.mhus.vance.shared.home.HomeBootstrapService;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Service;
import org.yaml.snakeyaml.Yaml;

/**
 * Cascade-aware skill loader. Skills live as document subtrees under
 * {@code skills/<name>/SKILL.md} (frontmatter + markdown body), with
 * sibling reference files in {@code skills/<name>/<path>}.
 *
 * <p>Cascade walks USER → PROJECT → VANCE → RESOURCE:
 * <ol>
 *   <li>{@code _user_<login>/skills/<name>/SKILL.md} — only when a
 *       user is in scope</li>
 *   <li>Then {@code DocumentService.lookupCascade} on the regular
 *       {@code project → _vance → classpath:vance-defaults/} chain.</li>
 * </ol>
 *
 * <p>Reference docs are resolved <b>against the layer that produced the
 * SKILL.md hit</b> — never re-cascaded — so a user's skill cannot
 * accidentally read a same-named reference file out of a different
 * cascade tier. The same applies to the bundled tier (sibling
 * classpath resources).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SkillLoader {

    /** Path prefix used for skill subtrees in any cascade tier. */
    public static final String SKILL_PATH_PREFIX = "skills/";

    /** Entry point file inside each skill folder. */
    public static final String SKILL_ENTRY_FILE = "SKILL.md";

    private static final String FRONTMATTER_FENCE = "---";
    private static final String BUNDLED_LISTING_PATTERN =
            "classpath*:" + DocumentService.RESOURCE_PREFIX
                    + SKILL_PATH_PREFIX + "*/" + SKILL_ENTRY_FILE;

    private final DocumentService documentService;
    private final PathMatchingResourcePatternResolver resourcePatternResolver =
            new PathMatchingResourcePatternResolver(getClass().getClassLoader());

    // ─── Single-skill load ─────────────────────────────────────────────────

    /**
     * Resolves {@code name} via USER → PROJECT → VANCE → RESOURCE.
     * Returns empty if no tier carries the skill, or if the matched
     * tier has the skill marked {@code enabled: false}.
     */
    public Optional<ResolvedSkill> load(
            String tenantId,
            @Nullable String userId,
            @Nullable String projectId,
            String name) {
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }
        String stem = name.toLowerCase().trim();
        String entryPath = entryPathFor(stem);

        // 1. USER layer (own user only — caller filters / enforces this).
        if (userId != null && !userId.isBlank()) {
            String userProject = HomeBootstrapService.HUB_PROJECT_NAME_PREFIX + userId;
            Optional<DocumentDocument> userDoc =
                    documentService.findByPath(tenantId, userProject, entryPath);
            if (userDoc.isPresent()) {
                return Optional.of(parse(stem, userDoc.get(), SkillScope.USER,
                        new ProjectSiblingReader(tenantId, userProject)));
            }
        }

        // 2. PROJECT → VANCE → RESOURCE via DocumentService cascade.
        String effectiveProjectId = (projectId == null || projectId.isBlank())
                ? HomeBootstrapService.VANCE_PROJECT_NAME : projectId;
        Optional<LookupResult> hit = documentService.lookupCascade(
                tenantId, effectiveProjectId, entryPath);
        if (hit.isEmpty()) return Optional.empty();
        LookupResult result = hit.get();

        SkillScope scope = mapScope(result.source());
        SiblingReader siblingReader = switch (result.source()) {
            case PROJECT -> new ProjectSiblingReader(tenantId, effectiveProjectId);
            case VANCE -> new ProjectSiblingReader(
                    tenantId, HomeBootstrapService.VANCE_PROJECT_NAME);
            case RESOURCE -> new ResourceSiblingReader();
        };
        return Optional.of(parse(stem, result, scope, siblingReader));
    }

    // ─── Listing ───────────────────────────────────────────────────────────

    /**
     * Lists the union of all skills visible in the given scope, with
     * cascade-deduplication by name (most specific scope wins).
     * Disabled skills are skipped.
     */
    public List<ResolvedSkill> listAvailable(
            String tenantId,
            @Nullable String userId,
            @Nullable String projectId) {
        Map<String, ResolvedSkill> byName = new LinkedHashMap<>();

        // Outer-to-inner so inner layers overwrite outer entries.
        for (ResolvedSkill r : listResources()) {
            if (r.enabled()) byName.put(r.name(), r);
        }
        addProjectLayer(byName, tenantId, HomeBootstrapService.VANCE_PROJECT_NAME, SkillScope.VANCE);
        if (projectId != null && !projectId.isBlank()
                && !HomeBootstrapService.VANCE_PROJECT_NAME.equals(projectId)) {
            addProjectLayer(byName, tenantId, projectId, SkillScope.PROJECT);
        }
        if (userId != null && !userId.isBlank()) {
            addProjectLayer(byName, tenantId,
                    HomeBootstrapService.HUB_PROJECT_NAME_PREFIX + userId,
                    SkillScope.USER);
        }
        return new ArrayList<>(byName.values());
    }

    // ─── Layer helpers ─────────────────────────────────────────────────────

    private void addProjectLayer(
            Map<String, ResolvedSkill> acc,
            String tenantId,
            String projectId,
            SkillScope scope) {
        SiblingReader siblings = new ProjectSiblingReader(tenantId, projectId);
        for (DocumentDocument doc : documentService.listByProject(tenantId, projectId)) {
            String path = doc.getPath();
            String stem = stemFromEntryPath(path);
            if (stem == null) continue;
            try {
                ResolvedSkill parsed = parse(stem, doc, scope, siblings);
                if (parsed.enabled()) {
                    acc.put(parsed.name(), parsed);
                } else {
                    // disabled overrides hide an outer skill of the same name
                    acc.remove(parsed.name());
                }
            } catch (RuntimeException e) {
                log.warn("SkillLoader: skipping malformed skill tenant='{}' project='{}' path='{}': {}",
                        tenantId, projectId, path, e.getMessage());
            }
        }
    }

    private List<ResolvedSkill> listResources() {
        Resource[] resources;
        try {
            resources = resourcePatternResolver.getResources(BUNDLED_LISTING_PATTERN);
        } catch (IOException e) {
            log.warn("Failed to scan bundled skills under '{}': {}",
                    BUNDLED_LISTING_PATTERN, e.toString());
            return List.of();
        }
        List<ResolvedSkill> out = new ArrayList<>(resources.length);
        ResourceSiblingReader siblings = new ResourceSiblingReader();
        for (Resource resource : resources) {
            if (!resource.exists() || !resource.isReadable()) continue;
            String stem = stemFromResource(resource);
            if (stem == null) continue;
            try (InputStream in = resource.getInputStream()) {
                String raw = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                out.add(parse(stem, raw, SkillScope.RESOURCE, siblings, stem));
            } catch (RuntimeException | IOException e) {
                log.warn("SkillLoader: skipping malformed bundled skill '{}': {}",
                        stem, e.getMessage());
            }
        }
        return out;
    }

    // ─── Parsing ───────────────────────────────────────────────────────────

    private static String entryPathFor(String stem) {
        return SKILL_PATH_PREFIX + stem + "/" + SKILL_ENTRY_FILE;
    }

    private static String siblingPathFor(String stem, String relativePath) {
        return SKILL_PATH_PREFIX + stem + "/" + relativePath;
    }

    private static @Nullable String stemFromEntryPath(@Nullable String path) {
        if (path == null) return null;
        if (!path.startsWith(SKILL_PATH_PREFIX)) return null;
        if (!path.endsWith("/" + SKILL_ENTRY_FILE)) return null;
        String inner = path.substring(SKILL_PATH_PREFIX.length(),
                path.length() - ("/" + SKILL_ENTRY_FILE).length());
        if (inner.isBlank() || inner.contains("/")) return null;
        return inner;
    }

    private static @Nullable String stemFromResource(Resource resource) {
        try {
            String uri = resource.getURI().toString();
            int suffix = uri.lastIndexOf("/" + SKILL_ENTRY_FILE);
            if (suffix < 0) return null;
            int dirStart = uri.lastIndexOf('/', suffix - 1);
            if (dirStart < 0) return null;
            return uri.substring(dirStart + 1, suffix);
        } catch (IOException e) {
            return null;
        }
    }

    private static SkillScope mapScope(LookupResult.Source source) {
        return switch (source) {
            case PROJECT -> SkillScope.PROJECT;
            case VANCE -> SkillScope.VANCE;
            case RESOURCE -> SkillScope.RESOURCE;
        };
    }

    private ResolvedSkill parse(
            String stem,
            DocumentDocument doc,
            SkillScope scope,
            SiblingReader siblings) {
        String content = readDocAsString(doc);
        return parse(stem, content, scope, siblings, stem);
    }

    private ResolvedSkill parse(
            String stem,
            LookupResult result,
            SkillScope scope,
            SiblingReader siblings) {
        String content = result.content() == null ? "" : result.content();
        return parse(stem, content, scope, siblings, stem);
    }

    private String readDocAsString(DocumentDocument doc) {
        String inline = doc.getInlineText();
        if (inline != null) return inline;
        try (InputStream in = documentService.loadContent(doc)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(
                    "Failed to read skill document id='" + doc.getId() + "': " + e.getMessage(), e);
        }
    }

    @SuppressWarnings("unchecked")
    private static ResolvedSkill parse(
            String stem,
            String raw,
            SkillScope scope,
            SiblingReader siblings,
            String skillFolderStem) {
        Frontmatter fm = splitFrontmatter(raw, stem);
        Yaml yaml = new Yaml();
        Object parsed = yaml.load(fm.frontmatter);
        if (parsed == null) {
            throw new IllegalStateException("skill '" + stem + "': empty frontmatter");
        }
        if (!(parsed instanceof Map<?, ?> m)) {
            throw new IllegalStateException(
                    "skill '" + stem + "': frontmatter must be a YAML map");
        }
        Map<String, Object> spec = (Map<String, Object>) m;

        String declaredName = stringOrNull(spec.get("name"));
        String name = declaredName == null ? stem : declaredName.toLowerCase().trim();
        if (declaredName != null && !declaredName.equalsIgnoreCase(stem)) {
            throw new IllegalStateException(
                    "skill folder '" + stem + "' does not match declared name '"
                            + declaredName + "'");
        }
        String title = requireString(spec, "title", stem);
        String description = requireString(spec, "description", stem);
        String version = requireString(spec, "version", stem);

        List<ResolvedSkill.Trigger> triggers = parseTriggers(spec.get("triggers"), stem);
        List<String> tools = stringList(spec.get("tools"), stem, "tools");
        List<String> tags = stringList(spec.get("tags"), stem, "tags");
        boolean enabled = !(spec.get("enabled") instanceof Boolean b) || b;
        String promptExtension = fm.body.isBlank() ? null : fm.body.strip();
        List<ResolvedSkill.ReferenceDoc> refDocs =
                parseReferenceDocs(spec.get("referenceDocs"), stem, skillFolderStem, siblings);

        return new ResolvedSkill(
                name, title, description, version,
                triggers, promptExtension, tools, refDocs, tags, enabled, scope);
    }

    private record Frontmatter(String frontmatter, String body) {}

    private static Frontmatter splitFrontmatter(String raw, String stem) {
        String text = raw.replace("\r\n", "\n");
        if (!text.startsWith(FRONTMATTER_FENCE)) {
            throw new IllegalStateException(
                    "skill '" + stem + "' must start with a YAML frontmatter fence '---'");
        }
        int firstFenceEnd = text.indexOf('\n');
        if (firstFenceEnd < 0) {
            throw new IllegalStateException(
                    "skill '" + stem + "' has no body after the opening fence");
        }
        int closingFence = text.indexOf("\n" + FRONTMATTER_FENCE, firstFenceEnd);
        if (closingFence < 0) {
            throw new IllegalStateException(
                    "skill '" + stem + "' is missing a closing '---' fence");
        }
        String frontmatter = text.substring(firstFenceEnd + 1, closingFence);
        int afterClosing = closingFence + ("\n" + FRONTMATTER_FENCE).length();
        String body = afterClosing >= text.length() ? "" : text.substring(afterClosing);
        if (body.startsWith("\n")) body = body.substring(1);
        return new Frontmatter(frontmatter, body);
    }

    @SuppressWarnings("unchecked")
    private static List<ResolvedSkill.Trigger> parseTriggers(Object raw, String stem) {
        if (raw == null) return List.of();
        if (!(raw instanceof List<?> list)) {
            throw new IllegalStateException(
                    "skill '" + stem + "': 'triggers' must be a list");
        }
        List<ResolvedSkill.Trigger> out = new ArrayList<>(list.size());
        for (int i = 0; i < list.size(); i++) {
            Object item = list.get(i);
            if (!(item instanceof Map<?, ?> mm)) {
                throw new IllegalStateException(
                        "skill '" + stem + "': triggers[" + i + "] must be a map");
            }
            Map<String, Object> spec = (Map<String, Object>) mm;
            String typeRaw = requireString(spec, "type", stem + " triggers[" + i + "]");
            SkillTriggerType type;
            try {
                type = SkillTriggerType.valueOf(typeRaw.trim().toUpperCase());
            } catch (IllegalArgumentException e) {
                throw new IllegalStateException(
                        "skill '" + stem + "': unknown trigger type '" + typeRaw + "'");
            }
            String pattern = stringOrNull(spec.get("pattern"));
            List<String> keywords = stringList(
                    spec.get("keywords"), stem, "triggers[" + i + "].keywords");
            switch (type) {
                case PATTERN -> {
                    if (pattern == null) {
                        throw new IllegalStateException(
                                "skill '" + stem + "': PATTERN trigger needs 'pattern'");
                    }
                }
                case KEYWORDS -> {
                    if (keywords.isEmpty()) {
                        throw new IllegalStateException(
                                "skill '" + stem + "': KEYWORDS trigger needs 'keywords'");
                    }
                }
            }
            out.add(new ResolvedSkill.Trigger(type, pattern, keywords));
        }
        return List.copyOf(out);
    }

    @SuppressWarnings("unchecked")
    private static List<ResolvedSkill.ReferenceDoc> parseReferenceDocs(
            Object raw, String stem, String skillFolderStem, SiblingReader siblings) {
        if (raw == null) return List.of();
        if (!(raw instanceof List<?> list)) {
            throw new IllegalStateException(
                    "skill '" + stem + "': 'referenceDocs' must be a list");
        }
        List<ResolvedSkill.ReferenceDoc> out = new ArrayList<>(list.size());
        for (int i = 0; i < list.size(); i++) {
            Object item = list.get(i);
            if (!(item instanceof Map<?, ?> mm)) {
                throw new IllegalStateException(
                        "skill '" + stem + "': referenceDocs[" + i + "] must be a map");
            }
            Map<String, Object> spec = (Map<String, Object>) mm;
            String title = requireString(spec, "title", stem + " referenceDocs[" + i + "]");
            String relativePath = requireString(spec, "file", stem + " referenceDocs[" + i + "]");
            SkillReferenceDocLoadMode loadMode = SkillReferenceDocLoadMode.INLINE;
            Object loadModeRaw = spec.get("loadMode");
            if (loadModeRaw instanceof String s && !s.isBlank()) {
                try {
                    loadMode = SkillReferenceDocLoadMode.valueOf(s.trim().toUpperCase());
                } catch (IllegalArgumentException e) {
                    throw new IllegalStateException(
                            "skill '" + stem + "': unknown loadMode '" + s + "'");
                }
            }
            String content = siblings.read(skillFolderStem, relativePath)
                    .orElseThrow(() -> new IllegalStateException(
                            "skill '" + stem + "': reference file '" + relativePath
                                    + "' not found in this layer"));
            out.add(new ResolvedSkill.ReferenceDoc(title, content, loadMode));
        }
        return List.copyOf(out);
    }

    private static String requireString(Map<String, Object> spec, String key, String context) {
        Object raw = spec.get(key);
        if (!(raw instanceof String s) || s.isBlank()) {
            throw new IllegalStateException(
                    context + ": missing required string '" + key + "'");
        }
        return s.trim();
    }

    private static @Nullable String stringOrNull(Object raw) {
        return raw instanceof String s && !s.isBlank() ? s : null;
    }

    @SuppressWarnings("unchecked")
    private static List<String> stringList(Object raw, String stem, String fieldName) {
        if (raw == null) return List.of();
        if (!(raw instanceof List<?> list)) {
            throw new IllegalStateException(
                    "skill '" + stem + "': '" + fieldName + "' must be a list");
        }
        List<String> out = new ArrayList<>(list.size());
        for (Object item : list) {
            if (!(item instanceof String s) || s.isBlank()) {
                throw new IllegalStateException(
                        "skill '" + stem + "': '" + fieldName
                                + "' contains a non-string or blank entry");
            }
            out.add(s);
        }
        return List.copyOf(out);
    }

    // ─── Sibling readers ───────────────────────────────────────────────────

    /** Reads a sibling file out of the same cascade tier the SKILL.md came from. */
    private interface SiblingReader {
        Optional<String> read(String skillFolderStem, String relativePath);
    }

    private final class ProjectSiblingReader implements SiblingReader {
        private final String tenantId;
        private final String projectId;

        ProjectSiblingReader(String tenantId, String projectId) {
            this.tenantId = tenantId;
            this.projectId = projectId;
        }

        @Override
        public Optional<String> read(String skillFolderStem, String relativePath) {
            String path = siblingPathFor(skillFolderStem, relativePath);
            return documentService.findByPath(tenantId, projectId, path)
                    .map(SkillLoader.this::readDocAsString);
        }
    }

    private final class ResourceSiblingReader implements SiblingReader {
        @Override
        public Optional<String> read(String skillFolderStem, String relativePath) {
            String resourcePath = "classpath:" + DocumentService.RESOURCE_PREFIX
                    + siblingPathFor(skillFolderStem, relativePath);
            Resource resource = resourcePatternResolver.getResource(resourcePath);
            if (!resource.exists() || !resource.isReadable()) {
                return Optional.empty();
            }
            try (InputStream in = resource.getInputStream()) {
                return Optional.of(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            } catch (IOException e) {
                throw new IllegalStateException(
                        "failed to read bundled sibling '" + relativePath + "': " + e.getMessage(), e);
            }
        }
    }
}
