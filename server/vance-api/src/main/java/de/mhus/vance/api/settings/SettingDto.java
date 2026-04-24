package de.mhus.vance.api.settings;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * Read view of a single setting.
 *
 * <p>For {@link SettingType#PASSWORD} the {@link #value} is masked as
 * {@code "[set]"} if a ciphertext exists and {@code null} if the setting is
 * empty — the plaintext never leaves the server through this endpoint.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("settings")
public class SettingDto {

    private String tenantId;

    private String referenceType;

    private String referenceId;

    private String key;

    private @Nullable String value;

    private SettingType type;

    private @Nullable String description;

    private @Nullable Instant createdAt;

    private @Nullable Instant updatedAt;
}
