package de.mhus.vance.api.skills;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import java.util.ArrayList;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * Read-only view of a skill for chat / picker UIs. Carries what the
 * user needs to recognise, pick and understand a skill — identity,
 * description, auto-triggers and the behavioural metadata (lifecycle,
 * tools, arguments, reference docs, scripts, command sequences). The
 * editing payload (the prompt-extension body) is not part of this
 * surface; editors go through the document layer directly.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("skills")
public class SkillSummaryDto {

    private String name;

    private String title;

    private String description;

    private String version;

    @Builder.Default
    private List<String> tags = new ArrayList<>();

    /**
     * Picker grouping key — the normalised {@code category:} frontmatter
     * field (skills.md §2, §4f). Display-only metadata like {@code title}:
     * activation, trigger and tool logic never read it. Group order and
     * localised labels come from {@code _vance/config/skill_categories.yaml},
     * shipped with the LIST response (see {@code ProcessSkillResponse#getCategories()}).
     * {@code null} means "no category" — those skills sort last.
     */
    private @Nullable String category;

    /**
     * Auto-activation configuration, shown so a user can see what a
     * skill reacts to before activating it manually. Read-only view of
     * the SKILL.md {@code triggers:} frontmatter (skills.md §2).
     */
    @Builder.Default
    private List<SkillTriggerDto> triggers = new ArrayList<>();

    /**
     * Lifecycle vocabulary: {@code sticky} (default — activation
     * persists, body injected every turn until cleared) or {@code shot}
     * (fires once as a prompt/config macro, never becomes active).
     * Lowercase, matching {@code ProcessSkillResponse#getLifecycle()}.
     */
    private String lifecycle;

    /** Tool names the skill adds to the turn's whitelist while active. */
    @Builder.Default
    private List<String> tools = new ArrayList<>();

    /** Manual folder paths the skill contributes to {@code manual_read} while active. */
    @Builder.Default
    private List<String> manualPaths = new ArrayList<>();

    /** Invocation arguments the skill binds trailing text into (skills.md §2b). */
    @Builder.Default
    private List<SkillArgumentDto> arguments = new ArrayList<>();

    /** Reference docs attached to the skill, with their load mode. */
    @Builder.Default
    private List<SkillReferenceDocDto> referenceDocs = new ArrayList<>();

    /** Scripts the skill mounts as virtual tools while active (skills.md §13). */
    @Builder.Default
    private List<SkillScriptDto> scripts = new ArrayList<>();

    /**
     * Engine-command sequence fired once on activation (skills.md §2a),
     * rendered back to the canonical {@code verb rest…} string form.
     */
    @Builder.Default
    private List<String> activate = new ArrayList<>();

    /**
     * Engine-command sequence fired on clear; never fired for
     * {@code lifecycle: shot} (skills.md §2a).
     */
    @Builder.Default
    private List<String> deactivate = new ArrayList<>();

    private boolean enabled;

    /** Cascade tier that produced this skill. */
    private SkillScope source;
}
