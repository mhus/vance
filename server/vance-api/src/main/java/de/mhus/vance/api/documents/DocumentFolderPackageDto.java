package de.mhus.vance.api.documents;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * A subfolder of a folder listing that is a <em>package</em>: it holds an
 * application manifest ({@code _app.yaml}) and is meant to be opened as a unit
 * rather than browsed, like a bundle in a file manager.
 *
 * <p>Carried in {@link DocumentFolderListResponse#getPackages()}. The folder
 * names themselves stay plain strings in {@code folders}; this list says which
 * of them are packages, so a client that does not know about packages keeps
 * working unchanged.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("documents")
public class DocumentFolderPackageDto {

    /** The folder's name — one path segment, as in {@code folders}. */
    private String name;

    /** {@code kind} of the manifest document, typically {@code application}. */
    private @Nullable String kind;

    /**
     * The application type the manifest declares ({@code kanban}, {@code wiki},
     * …). A client picks the icon from it; absent means a generic package.
     */
    private @Nullable String app;
}
