package de.mhus.vance.brain.skill;

import de.mhus.vance.api.skills.SkillReferenceDocLoadMode;
import de.mhus.vance.api.skills.SkillScope;
import de.mhus.vance.api.skills.SkillTriggerType;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Cascade-resolved view of one skill — what callers (Ford,
 * SkillPromptComposer, Arthur's auto-trigger detector) consume. Same
 * shape as {@link BundledSkill} plus the source attribution so the UI
 * can mark "from project / from tenant / from bundled" etc.
 */
public record ResolvedSkill(
        String name,
        String title,
        String description,
        String version,
        List<Trigger> triggers,
        @Nullable String promptExtension,
        List<String> tools,
        List<ReferenceDoc> referenceDocs,
        List<String> tags,
        boolean enabled,
        SkillScope source) {

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
}
