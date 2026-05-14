package de.mhus.vance.api.kit;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * Request body for the catalog scan endpoint
 * ({@code POST /brain/{tenant}/admin/project-kits/scan}): clone a git
 * repo, scan its {@code kits/} subdir, return a fresh
 * {@link ProjectKitsCatalogDto} without persisting anything.
 *
 * <p>{@code gitUrl} is required; {@code ref} defaults to {@code main}
 * when blank. {@code token} carries an optional credential for private
 * repos — the scan never persists it, so anus has to send it on every
 * call.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("kit")
public class ProjectKitsScanRequestDto {

    private String gitUrl;

    private @Nullable String ref;

    private @Nullable String token;
}
