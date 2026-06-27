package de.mhus.vance.api.documents;

import de.mhus.vance.api.annotations.GenerateTypeScript;
import java.util.EnumSet;
import java.util.Set;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * Body of {@code PATCH /brain/{tenant}/documents/{id}/lock} — sets the
 * complete {@code lockedFor} set. Server-side normalisation (see
 * {@code DocumentService.normalizeLockedFor}) auto-adds {@code AI}
 * whenever {@code USER} or {@code KIT} is present.
 *
 * <p>{@code null} or empty {@code lockedFor} clears the lock.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@GenerateTypeScript("documents")
public class DocumentLockRequest {

    /** The desired set of writer roles to block. */
    @Builder.Default
    private @Nullable Set<WriterRole> lockedFor = EnumSet.noneOf(WriterRole.class);
}
