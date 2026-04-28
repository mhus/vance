package de.mhus.vance.brain.skill;

import de.mhus.vance.api.skills.SkillReferenceDocLoadMode;
import de.mhus.vance.api.skills.SkillScriptTarget;
import de.mhus.vance.api.skills.SkillTriggerType;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Immutable in-memory snapshot of one bundled skill, parsed from its
 * {@code SKILL.md} resource at boot. Fields mirror {@code SkillDocument}
 * so a bundled and a Mongo-stored skill can be treated interchangeably
 * by the resolver.
 */
public record BundledSkill(
        String name,
        String title,
        String description,
        String version,
        List<Trigger> triggers,
        @Nullable String promptExtension,
        List<String> tools,
        List<ReferenceDoc> referenceDocs,
        List<Script> scripts,
        List<String> tags,
        boolean enabled) {

    public record Trigger(
            SkillTriggerType type,
            @Nullable String pattern,
            List<String> keywords) {
    }

    public record ReferenceDoc(
            String title,
            String content,
            SkillReferenceDocLoadMode loadMode) {
    }

    public record Script(
            String name,
            @Nullable String description,
            SkillScriptTarget target,
            String content) {
    }
}
