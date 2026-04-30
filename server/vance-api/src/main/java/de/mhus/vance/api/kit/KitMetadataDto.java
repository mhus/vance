package de.mhus.vance.api.kit;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * Embedded summary of a kit's identity — copied from
 * {@link KitDescriptorDto} into {@link KitManifestDto} so the manifest
 * is self-describing without re-fetching the source repo.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("kit")
public class KitMetadataDto {

    private String name;

    private String description;

    private @Nullable String version;
}
