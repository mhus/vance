package de.mhus.vance.api.documents;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * One mounted external source, as a client sees it.
 *
 * <p>Wire counterpart of {@code MountedSource}, and deliberately not that
 * record itself: the internal one carries the metadata TTL, which is cache
 * policy nobody outside needs, and a {@code Duration} on the wire would be one
 * more thing for a client to parse for no benefit.
 *
 * <p>Exists because a mounted folder can be empty for reasons the folder
 * listing cannot express. An unreachable source and an actually empty
 * directory look identical in a list of files — and a reader who cannot tell
 * them apart concludes the documents are gone. {@link #statusText} is that
 * distinction.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("documents")
public class MountDto {

    /** The mount name — the segment in {@code _ext/<name>/…}. */
    private String name;

    /** Label the source prefers; {@code null} means use {@link #name}. */
    private String displayName;

    /** Which protocol serves it, for diagnostics. */
    private String protocolId;

    /**
     * What the source allows. {@code UNKNOWN} means the declaration is not
     * loaded yet, not that writing is forbidden — the guard is the document
     * lock, not this field.
     */
    private MountAccess access;

    /**
     * How much the source says it holds; {@code null} means it does not say.
     * Not zero — a client showing 0 for an unknown size reads as "empty".
     */
    private Long itemCount;

    /**
     * Why the source is not answering, when it is not. {@code null} when
     * everything is fine, which is the common case.
     */
    private String statusText;

    /**
     * Whether a search can be handed to this source. Clients use it to decide
     * whether to offer one at all rather than filtering what happens to be
     * cached — see {@link MountSearchOutcome}.
     */
    private boolean canSearch;
}
