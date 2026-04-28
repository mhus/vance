package de.mhus.vance.brain.skill;

import de.mhus.vance.api.skills.SkillScope;
import de.mhus.vance.shared.skill.SkillDocument;
import de.mhus.vance.shared.skill.SkillReferenceDocEmbedded;
import de.mhus.vance.shared.skill.SkillService;
import de.mhus.vance.shared.skill.SkillTriggerEmbedded;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Cascade resolver for skill names — USER → PROJECT → TENANT → BUNDLED.
 * Combines persistent (Mongo, via {@link SkillService}) and bundled
 * (classpath, via {@link BundledSkillRegistry}) tiers.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SkillResolver {

    private final SkillService skillService;
    private final BundledSkillRegistry bundled;

    /**
     * Resolves {@code name} in the user/project/tenant/bundled cascade.
     * Returns empty if no tier carries the name. Disabled skills count
     * as misses.
     */
    public Optional<ResolvedSkill> resolve(SkillScopeContext ctx, String name) {
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }
        Optional<SkillDocument> persistent = skillService.find(
                ctx.tenantId(), ctx.userId(), ctx.projectId(), name);
        if (persistent.isPresent()) {
            return Optional.of(fromDocument(persistent.get()));
        }
        return bundled.find(name)
                .filter(BundledSkill::enabled)
                .map(SkillResolver::fromBundled);
    }

    /**
     * Resolves multiple skill names in order. Unknown names throw —
     * partial activation would be confusing for users explicitly
     * requesting a skill set (Recipe-bound skills, /skill spam).
     */
    public List<ResolvedSkill> resolveAll(SkillScopeContext ctx, List<String> names) {
        if (names == null || names.isEmpty()) {
            return List.of();
        }
        List<ResolvedSkill> out = new ArrayList<>(names.size());
        for (String name : names) {
            ResolvedSkill skill = resolve(ctx, name)
                    .orElseThrow(() -> new UnknownSkillException(name));
            out.add(skill);
        }
        return out;
    }

    /**
     * Lists the union of all skills visible in the given scope, with
     * cascade-deduplication by name (most specific scope wins).
     * Disabled skills are skipped. Used by Arthur's auto-trigger
     * detector and by the skill-picker UI.
     */
    public List<ResolvedSkill> listAvailable(SkillScopeContext ctx) {
        Map<String, ResolvedSkill> byName = new LinkedHashMap<>();

        for (SkillDocument doc : skillService.listAvailable(
                ctx.tenantId(), ctx.userId(), ctx.projectId())) {
            byName.putIfAbsent(doc.getName(), fromDocument(doc));
        }
        for (BundledSkill b : bundled.all()) {
            if (b.enabled()) {
                byName.putIfAbsent(b.name(), fromBundled(b));
            }
        }
        return new ArrayList<>(byName.values());
    }

    private static ResolvedSkill fromDocument(SkillDocument d) {
        List<ResolvedSkill.Trigger> triggers = new ArrayList<>();
        if (d.getTriggers() != null) {
            for (SkillTriggerEmbedded t : d.getTriggers()) {
                triggers.add(new ResolvedSkill.Trigger(
                        t.getType(),
                        t.getPattern(),
                        t.getKeywords() == null ? List.of() : List.copyOf(t.getKeywords())));
            }
        }
        List<ResolvedSkill.ReferenceDoc> docs = new ArrayList<>();
        if (d.getReferenceDocs() != null) {
            for (SkillReferenceDocEmbedded r : d.getReferenceDocs()) {
                docs.add(new ResolvedSkill.ReferenceDoc(
                        r.getTitle(), r.getContent(), r.getLoadMode()));
            }
        }
        return new ResolvedSkill(
                d.getName(),
                d.getTitle(),
                d.getDescription(),
                d.getVersion(),
                List.copyOf(triggers),
                d.getPromptExtension(),
                d.getTools() == null ? List.of() : List.copyOf(d.getTools()),
                List.copyOf(docs),
                d.getTags() == null ? List.of() : List.copyOf(d.getTags()),
                d.isEnabled(),
                d.getScope());
    }

    private static ResolvedSkill fromBundled(BundledSkill b) {
        List<ResolvedSkill.Trigger> triggers = new ArrayList<>(b.triggers().size());
        for (BundledSkill.Trigger t : b.triggers()) {
            triggers.add(new ResolvedSkill.Trigger(t.type(), t.pattern(), t.keywords()));
        }
        List<ResolvedSkill.ReferenceDoc> docs = new ArrayList<>(b.referenceDocs().size());
        for (BundledSkill.ReferenceDoc r : b.referenceDocs()) {
            docs.add(new ResolvedSkill.ReferenceDoc(r.title(), r.content(), r.loadMode()));
        }
        return new ResolvedSkill(
                b.name(),
                b.title(),
                b.description(),
                b.version(),
                List.copyOf(triggers),
                b.promptExtension(),
                b.tools(),
                List.copyOf(docs),
                b.tags(),
                b.enabled(),
                SkillScope.BUNDLED);
    }
}
