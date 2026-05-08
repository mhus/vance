package de.mhus.vance.api.thinkprocess;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * Editor-context metadata attached to a {@link ProcessSteerRequest} when the
 * client is connected to an IDE plugin (Claude Code's JetBrains/VSCode
 * extension via the foot ↔ IDE bridge — see
 * {@code planning/foot-ide-bridge.md}).
 *
 * <p>Carries pointers, not content. The brain renders the metadata into the
 * prompt as {@code <ide-at-mention/>} and {@code <ide-selection/>} tags so
 * the LLM knows what the user is looking at; the actual buffer content is
 * fetched on demand via the IDE-tools (tool-pack registered while the
 * bridge is connected) or {@code client_file_read}.
 *
 * <p>Both fields are optional. The client only fills them when the bridge is
 * up and the IDE has reported the corresponding event. Lines are 1-based,
 * inclusive on both ends — the foot converts from the plugin's 0-based
 * positions before sending.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("thinkprocess")
public class IdeContext {

    /**
     * Most recent {@code at_mentioned} event consumed by this send. The
     * user explicitly pressed "Send to Claude" (Cmd+Esc / Alt+Enter)
     * before typing this message. Single-shot: the foot clears its
     * pending buffer after attaching it to one steer.
     */
    private @Nullable IdeFileRange atMention;

    /**
     * Live editor selection at the moment of send. Snapshot, not pinned —
     * the next steer carries whatever is current then.
     */
    private @Nullable IdeFileRange currentSelection;
}
