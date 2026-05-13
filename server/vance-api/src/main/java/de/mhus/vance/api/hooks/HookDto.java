package de.mhus.vance.api.hooks;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import java.util.List;
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
 * <p>See {@code specification/hooks.md} §4 for the YAML schema.
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

    // ─── Parsed convenience fields ───

    private HookType type;
    private boolean enabled;
    private @Nullable String description;
    /** Wall-clock timeout in milliseconds. */
    private long timeoutMs;
    private @Nullable List<String> tags;

    /** Filled when {@link #type} == {@link HookType#JS}. */
    private @Nullable String script;

    /** Filled when {@link #type} == {@link HookType#LLM}. */
    private @Nullable String model;
    /** Filled when {@link #type} == {@link HookType#LLM}. */
    private @Nullable Integer maxTokens;
    /** Filled when {@link #type} == {@link HookType#LLM}. */
    private @Nullable String prompt;
}
