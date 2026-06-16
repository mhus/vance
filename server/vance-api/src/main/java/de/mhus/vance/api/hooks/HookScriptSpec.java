package de.mhus.vance.api.hooks;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * Wire shape for a hook's {@code script:} action — mirrors
 * {@code TriggerAction.Script} on the wire. Exactly the same fields
 * the {@code script:} block carries in scheduler/event/workflow YAMLs;
 * exposed as a separate DTO so the TypeScript generator emits a clean
 * interface for the web-UI editor.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("hooks")
public class HookScriptSpec {

    /** {@code "document"} or {@code "workspace"}. */
    private String source;

    /** Required when {@link #source} is {@code "workspace"}. */
    private @Nullable String dirName;

    /** Path within the document layer (DOCUMENT) or RootDir (WORKSPACE). */
    private String path;

    /** Optional per-call wall-clock timeout in seconds. */
    private @Nullable Integer timeoutSeconds;
}
