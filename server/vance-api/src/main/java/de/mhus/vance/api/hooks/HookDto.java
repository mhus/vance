package de.mhus.vance.api.hooks;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import java.util.List;
import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * Full hook document for the editor — both the parsed fields and the
 * raw YAML body. The YAML is the source of truth (round-trips verbatim,
 * including comments); parsed fields are convenience for the UI to
 * render the form without re-parsing.
 *
 * <p>Hook actions follow the unified {@code TriggerAction} schema:
 * exactly one of {@link #recipe}, {@link #script}, {@link #workflow}
 * is non-null. See {@code specification/hooks.md} and
 * {@code specification/trigger-actions.md} for the YAML schema.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("hooks")
public class HookDto {

    /** Hook name — last path segment without extension, not stored in the YAML body. */
    private String name;

    /** Event name (wire form, e.g. {@code "process.completed"}). */
    private String event;

    /** Verbatim YAML body as stored in the document layer. */
    private String yaml;

    /** Which cascade tier currently provides this hook. */
    private HookSource source;

    // ─── Parsed convenience fields (mirror of YAML for the UI) ───

    private boolean enabled;
    private @Nullable String description;
    /** Wall-clock timeout in milliseconds. */
    private long timeoutMs;
    private @Nullable List<String> tags;

    // ─── Action variant — exactly one is set ───

    /** Recipe name when the action is {@code recipe:}. */
    private @Nullable String recipe;

    /** Workflow name when the action is {@code workflow:}. */
    private @Nullable String workflow;

    /** Script descriptor when the action is {@code script:}. */
    private @Nullable HookScriptSpec script;

    /** Action params (merged into the executor's params bag). */
    private @Nullable Map<String, Object> params;

    /** Initial chat message — only meaningful for {@link #recipe}-actions. */
    private @Nullable String initialMessage;

    /** User identity to run as — {@code null} falls back to the hook's {@code createdBy}. */
    private @Nullable String runAs;
}
