package de.mhus.vance.api.starred;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * Wire view of one starred document — a pointer into any project of the tenant
 * plus the denormalised facts needed to render and open it without reading the
 * target.
 *
 * <p>{@code kind} and {@code type} are two axes on purpose: {@code kind} is the
 * document kind (always set), {@code type} is the {@code app:} of an application
 * manifest and absent for everything else. A "send to" looks up {@code type}.
 *
 * <p>{@code hidden} entries are <b>not</b> in the list the landing page fetches —
 * they are filtered server-side, not by a {@code v-if}. The flag travels only in
 * the management view, so the UI can show the state it would toggle.
 *
 * <p>{@code broken} is <b>wire-only and transient</b>: it is set from a reconcile
 * report or a failed open, never persisted. The control file stores the user's
 * curation; whether a target is currently reachable is not part of it.
 *
 * <p>See {@code planning/starred-documents.md}.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("starred")
public class StarredItemDto {

    /** Project the target lives in — the project {@code name}, not its id. */
    private String project;

    /** Project-relative document path. Together with {@code project} the entry's key. */
    private String path;

    /** Document kind, {@code text} when the target carries no header. */
    private String kind;

    /** App type of an {@code application} target; absent otherwise. */
    private @Nullable String type;

    private @Nullable String title;

    private @Nullable String description;

    /** Visual emphasis only — it never influences a lookup. */
    private boolean highlight;

    /** Registered with the service. */
    private boolean enabled;

    /** Registered but not shown on the landing page. */
    private boolean hidden;

    /** Transient: the last reconcile or open could not resolve this entry. */
    private @Nullable Boolean broken;
}
