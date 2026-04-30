package de.mhus.vance.api.kit;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import java.util.ArrayList;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * Parsed contents of a {@code kit.yaml} descriptor — the file lives at
 * the root of every kit-repo (or sub-path within a mono-repo) and
 * describes name, description, inherits and metadata flags.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("kit")
public class KitDescriptorDto {

    private String name;

    private String description;

    private @Nullable String version;

    @Builder.Default
    private List<KitInheritDto> inherits = new ArrayList<>();

    /**
     * Set to {@code true} when the kit (or any of its inherits) ships
     * PASSWORD-type settings. Importers must prompt for the vault
     * passphrase before installation; installers without a passphrase
     * skip PASSWORD-settings and log a warning.
     */
    @Builder.Default
    private boolean hasEncryptedSecrets = false;
}
