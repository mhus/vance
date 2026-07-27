package de.mhus.vance.api.documents;

import de.mhus.vance.api.annotations.GenerateTypeScript;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * Body of {@code POST /brain/{tenant}/documents/export}.
 *
 * <p>The server streams a ZIP archive keyed by each document's path so the
 * (virtual) folder structure is preserved. The selection is the union of
 * {@link #ids} (explicit documents) and {@link #folders} (path prefixes, each
 * expanded server-side to every document beneath it). Everything is resolved
 * and {@code READ}-authorized up front — the ZIP body only starts once the
 * whole set passed, so a forbidden entry fails cleanly instead of producing a
 * corrupt archive. At least one of {@code ids} / {@code folders} must be
 * non-empty (enforced by the controller).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@GenerateTypeScript("documents")
public class DocumentExportRequest {

    /** Explicit document ids to include. */
    private @Nullable List<String> ids;

    /**
     * Folder prefixes to expand (recursive). Each should end with {@code '/'}
     * (e.g. {@code notes/archive/}). Members are unioned with {@link #ids} and
     * de-duplicated.
     */
    private @Nullable List<String> folders;
}
