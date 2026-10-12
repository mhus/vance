package de.mhus.vance.brain.skill;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import de.mhus.vance.api.skills.SkillActivation;
import de.mhus.vance.api.skills.SkillActivationSource;
import de.mhus.vance.api.skills.SkillModeScope;
import de.mhus.vance.api.skills.SkillScope;
import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.shared.document.DocumentStatus;
import de.mhus.vance.shared.home.HomeBootstrapService;
import de.mhus.vance.shared.permission.WriteActor;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.yaml.snakeyaml.Yaml;

/**
 * Effective activation mode resolution — the scope-level override docs on
 * top of each skill's frontmatter declaration
 * (planning/skill-activation-levels.md §3.4).
 *
 * <p>The override document is {@code _vance/config/skill_activation.yaml},
 * a map of skill name → mode:
 *
 * <pre>
 * skills:
 *   code-review: agent
 *   typescript-style: manual
 * </pre>
 *
 * <p>Three tiers: {@code _tenant} → project → {@code _user_<login>}.
 * <b>Merged per entry</b>, innermost tier wins per skill name: a user
 * document with one line overrides exactly that one skill and leaves the
 * project's and the tenant's entries for every other skill in effect.
 * This deliberately differs from the skill cascade and the categories
 * document (whole-document first-hit-wins): those documents are one unit
 * each, this one is a list of independent per-skill decisions — the whole
 * reason it exists is overriding a single field without forking the
 * SKILL.md. The bundled resource layer ships no activation document, so
 * it is not read.
 *
 * <p>Fail-open on read like every config document: a malformed document
 * is logged and its tier skipped, a malformed <em>entry</em> is skipped
 * with a warning while the rest of the document stays effective — a
 * broken config file must not stop anyone from listing, activating or
 * advertising skills. The write path is strict instead
 * ({@link #setMode}): it refuses to rewrite a malformed document, because
 * the rewrite would silently drop its entries.
 *
 * <p><b>Cache.</b> Every chat turn reads the overrides (trigger matcher +
 * advertising), so the parsed content of each tier document is cached per
 * {@code (tenant, project)} — the merge itself is a cheap in-memory step
 * and runs per call, which keeps a {@code _tenant} change from having to
 * evict every merged view. Invalidation is data-driven: {@link
 * SkillActivationDocumentListener} evicts the tier on every routed
 * document-change event for {@link #ACTIVATION_CONFIG_PATH}. The TTL is
 * the safety net for the pods the router does not notify (a write inside
 * a normal project reaches only the writer and the lease holder, a user
 * hub write only the writer — specification/document-change-events.md).
 * Consumers still call {@link #overrides} once per turn / listing and
 * hand the result through {@link #resolve(ActivationOverrides,
 * ResolvedSkill)}, never once per skill.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SkillActivationService {

    /** Path of the activation override document in every tier. */
    public static final String ACTIVATION_CONFIG_PATH = "_vance/config/skill_activation.yaml";

    /** Safety-net expiry for pods that miss the change event. */
    static final Duration TIER_CACHE_TTL = Duration.ofSeconds(60);

    private final DocumentService documentService;
    private final SkillResolver skillResolver;

    /**
     * Parsed entries per tier document, keyed by {@code (tenant, project)}.
     * An absent or malformed document caches as an empty map — both mean
     * "this tier contributes nothing" on the read path.
     */
    private final Cache<TierKey, Map<String, SkillActivation>> tierCache = Caffeine.newBuilder()
            .expireAfterWrite(TIER_CACHE_TTL)
            .maximumSize(10_000)
            .build();

    private record TierKey(String tenantId, String projectId) {}

    /** One override entry: the mode and the tier that set it. */
    public record Override(SkillActivation mode, SkillScope scope) {}

    /**
     * The scope's merged mode overrides, read once per consumer (turn,
     * listing, activation) — never once per skill. Each entry carries the
     * tier it came from, so the panel can show which layer decided.
     */
    public record ActivationOverrides(Map<String, Override> byName) {

        public static final ActivationOverrides EMPTY = new ActivationOverrides(Map.of());

        public @Nullable SkillActivation overrideFor(String skillName) {
            Override o = byName.get(skillName);
            return o == null ? null : o.mode();
        }

        public @Nullable SkillScope scopeFor(String skillName) {
            Override o = byName.get(skillName);
            return o == null ? null : o.scope();
        }

        public boolean isEmpty() {
            return byName.isEmpty();
        }
    }

    /**
     * Outcome of a mode resolution: the effective mode plus its provenance
     * (the API enum directly — the panel shows the same value).
     * {@code overrideScope} names the tier when {@code source} is
     * {@link SkillActivationSource#OVERRIDE}.
     */
    public record ActivationResolution(
            SkillActivation mode,
            SkillActivationSource source,
            @Nullable SkillScope overrideScope) {}

    /**
     * Reads and merges the scope's override documents outer to inner —
     * {@code _tenant}, then the project, then the user's own hub project —
     * so a later {@code put} lets the innermost tier win per skill name.
     */
    public ActivationOverrides overrides(SkillScopeContext scope) {
        Map<String, Override> merged = new LinkedHashMap<>();
        applyTier(merged, scope.tenantId(), HomeBootstrapService.TENANT_PROJECT_NAME, SkillScope.VANCE);
        String projectId = scope.projectId();
        if (projectId != null && !projectId.isBlank() && !HomeBootstrapService.TENANT_PROJECT_NAME.equals(projectId)) {
            applyTier(merged, scope.tenantId(), projectId, SkillScope.PROJECT);
        }
        String userId = scope.userId();
        if (userId != null && !userId.isBlank()) {
            applyTier(merged, scope.tenantId(), HomeBootstrapService.hubProjectName(userId), SkillScope.USER);
        }
        return merged.isEmpty() ? ActivationOverrides.EMPTY : new ActivationOverrides(Map.copyOf(merged));
    }

    private void applyTier(Map<String, Override> acc, String tenantId, String projectId, SkillScope tier) {
        Map<String, SkillActivation> entries =
                tierCache.get(new TierKey(tenantId, projectId), key -> loadTier(key.tenantId(), key.projectId()));
        entries.forEach((name, mode) -> acc.put(name, new Override(mode, tier)));
    }

    private Map<String, SkillActivation> loadTier(String tenantId, String projectId) {
        Optional<DocumentDocument> doc = findActive(tenantId, projectId);
        if (doc.isEmpty()) return Map.of();
        Map<String, SkillActivation> entries = parse(readDocAsString(doc.get()), projectId);
        return entries == null ? Map.of() : Map.copyOf(entries);
    }

    /**
     * Drops one tier document's cached entries — called by {@link
     * SkillActivationDocumentListener} on every change to the override
     * document in that project. Idempotent.
     */
    public void evictTier(String tenantId, String projectId) {
        tierCache.invalidate(new TierKey(tenantId, projectId));
    }

    /**
     * Resolves the skill's effective activation mode: override wins, else
     * the frontmatter declaration, else the {@code triggers:} derivation.
     */
    public ActivationResolution resolve(ActivationOverrides overrides, ResolvedSkill skill) {
        Override override = overrides.byName().get(skill.name());
        if (override != null) {
            return new ActivationResolution(override.mode(), SkillActivationSource.OVERRIDE, override.scope());
        }
        if (skill.declaredActivation() != null) {
            return new ActivationResolution(skill.declaredActivation(), SkillActivationSource.FRONTMATTER, null);
        }
        return new ActivationResolution(skill.frontmatterActivation(), SkillActivationSource.DERIVED, null);
    }

    /** One-shot convenience for single-skill callers; loops must use {@link #overrides} once. */
    public ActivationResolution resolve(SkillScopeContext scope, ResolvedSkill skill) {
        return resolve(overrides(scope), skill);
    }

    /**
     * Writes one skill's mode override into the target tier's
     * {@code _vance/config/skill_activation.yaml} — merge, never replace:
     * the other entries of that tier's document survive.
     *
     * <p>Authorization is enforced at the document source (F1): the user
     * tier is the caller's own hub project (always theirs), project and
     * tenant go through the regular document CREATE/WRITE check. The
     * skill must resolve in the caller's scope — an override for a
     * typo'd name would look like it worked while doing nothing.
     *
     * <p>Returns the <em>effective</em> resolution after the write, not
     * the requested one: when an inner tier already overrides the same
     * skill (e.g. the caller's own user document while writing the
     * project tier), that inner entry keeps winning and the reply says so.
     *
     * <p>Programmatic rewrite note: the document is regenerated from the
     * parsed entries, so hand-written comments in it do not survive a
     * mode write. A malformed existing document is refused instead of
     * rewritten — the rewrite would drop its entries.
     */
    public ActivationResolution setMode(
            String tenantId,
            @Nullable String userId,
            @Nullable String projectId,
            String skillName,
            SkillActivation mode,
            SkillModeScope targetScope,
            WriteActor actor) {
        if (skillName == null || skillName.isBlank()) {
            throw new IllegalArgumentException("skill name is required");
        }
        String name = skillName.trim().toLowerCase(Locale.ROOT);
        SkillScopeContext scope = SkillScopeContext.of(tenantId, userId, projectId);
        if (skillResolver.resolve(scope, name).isEmpty()) {
            throw new IllegalArgumentException(
                    "skill '" + name + "' does not resolve in this scope — nothing to override");
        }

        String targetProject =
                switch (targetScope) {
                    case USER -> {
                        if (userId == null || userId.isBlank()) {
                            throw new IllegalArgumentException(
                                    "no user in scope — the 'user' override tier needs a logged-in user");
                        }
                        yield HomeBootstrapService.hubProjectName(userId);
                    }
                    case PROJECT -> {
                        if (projectId == null || projectId.isBlank()) {
                            throw new IllegalArgumentException(
                                    "no project in scope — the 'project' override tier needs a project");
                        }
                        yield projectId;
                    }
                    case TENANT -> HomeBootstrapService.TENANT_PROJECT_NAME;
                };
        SkillScope writtenTier =
                switch (targetScope) {
                    case USER -> SkillScope.USER;
                    case PROJECT -> SkillScope.PROJECT;
                    case TENANT -> SkillScope.VANCE;
                };

        // Read the merged view before the write: it tells whether an inner
        // tier keeps shadowing the entry we are about to write.
        ActivationOverrides before = overrides(scope);

        Map<String, SkillActivation> merged = new LinkedHashMap<>();
        Optional<DocumentDocument> existing = findActive(tenantId, targetProject);
        if (existing.isPresent()) {
            Map<String, SkillActivation> parsed = parse(readDocAsString(existing.get()), targetProject);
            if (parsed == null) {
                throw new IllegalArgumentException("the existing " + ACTIVATION_CONFIG_PATH + " in '" + targetProject
                        + "' is malformed — fix it in the document editor first, a rewrite would drop its entries");
            }
            merged.putAll(parsed);
        }
        merged.put(name, mode);
        String content = serialize(merged);

        if (existing.isPresent()) {
            documentService.update(existing.get().getId(), null, null, content, null, actor);
        } else {
            documentService.createText(
                    tenantId,
                    targetProject,
                    ACTIVATION_CONFIG_PATH,
                    /*title*/ "Skill activation overrides",
                    /*tags*/ List.of(),
                    content,
                    /*createdBy*/ userId != null && !userId.isBlank()
                            ? userId
                            : actor.reason().name(),
                    actor);
        }

        SkillScope shadowTier = before.scopeFor(name);
        if (shadowTier != null && rank(shadowTier) > rank(writtenTier)) {
            SkillActivation shadowMode = before.overrideFor(name);
            return new ActivationResolution(
                    shadowMode == null ? mode : shadowMode, SkillActivationSource.OVERRIDE, shadowTier);
        }
        return new ActivationResolution(mode, SkillActivationSource.OVERRIDE, writtenTier);
    }

    /** Tier order for the per-entry merge: higher is further inside. */
    private static int rank(SkillScope tier) {
        return switch (tier) {
            case USER -> 3;
            case PROJECT -> 2;
            case VANCE -> 1;
            case RESOURCE -> 0;
        };
    }

    /**
     * Serializes the override map in stable (alphabetical) order — the
     * document diff after a mode write shows exactly the one changed
     * entry.
     */
    private static String serialize(Map<String, SkillActivation> byName) {
        StringBuilder out = new StringBuilder("skills:\n");
        byName.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(e -> out.append("  ")
                        .append(e.getKey())
                        .append(": ")
                        .append(e.getValue().wireName())
                        .append('\n'));
        return out.toString();
    }

    // ──────────────────── document format ────────────────────

    private Optional<DocumentDocument> findActive(String tenantId, String projectId) {
        return documentService
                .findByPath(tenantId, projectId, ACTIVATION_CONFIG_PATH)
                .filter(doc -> doc.getStatus() == DocumentStatus.ACTIVE);
    }

    /**
     * Document content as UTF-8 string — same read path the skill loader
     * uses for document-backed tiers: inline content when the document
     * carries it, else the storage stream.
     */
    private String readDocAsString(DocumentDocument doc) {
        String inline = documentService.readContent(doc);
        if (inline != null) return inline;
        try (InputStream in = documentService.loadContent(doc)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(
                    "Failed to read activation config id='" + doc.getId() + "': " + e.getMessage(), e);
        }
    }

    /**
     * Parses one override document. {@code null} when the document as a
     * whole is malformed — the read path skips that tier (fail-open), the
     * write path refuses to rewrite it. An empty document is an empty map.
     * Individual entries with an unknown mode are skipped with a warning;
     * the rest stays effective.
     */
    private static @Nullable Map<String, SkillActivation> parse(String content, String projectId) {
        try {
            Object parsed = new Yaml().load(content);
            if (parsed == null) return Map.of();
            if (!(parsed instanceof Map<?, ?> m) || !m.containsKey("skills")) {
                log.warn(
                        "SkillActivationService: ignoring malformed activation document in '{}' (missing 'skills:' map)",
                        projectId);
                return null;
            }
            Object skills = m.get("skills");
            if (skills == null) return Map.of();
            if (!(skills instanceof Map<?, ?> entries)) {
                log.warn(
                        "SkillActivationService: ignoring malformed activation document in '{}' ('skills' is not a map)",
                        projectId);
                return null;
            }
            Map<String, SkillActivation> byName = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : entries.entrySet()) {
                if (!(entry.getKey() instanceof String name) || name.isBlank()) continue;
                Object raw = entry.getValue();
                SkillActivation mode = raw instanceof String s ? SkillActivation.parse(s) : null;
                if (mode == null) {
                    log.warn(
                            "SkillActivationService: skipping activation override '{}' → '{}' in '{}' (expected manual|trigger|agent)",
                            name,
                            raw,
                            projectId);
                    continue;
                }
                byName.put(name.trim().toLowerCase(Locale.ROOT), mode);
            }
            return byName;
        } catch (RuntimeException e) {
            log.warn(
                    "SkillActivationService: ignoring malformed activation document in '{}': {}",
                    projectId,
                    e.getMessage());
            return null;
        }
    }
}
