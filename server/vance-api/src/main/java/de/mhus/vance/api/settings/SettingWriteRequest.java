package de.mhus.vance.api.settings;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * Body of {@code PUT /brain/{tenant}/admin/settings/{referenceType}/{referenceId}/{key}}.
 *
 * <p>For {@link SettingType#PASSWORD} the {@link #value} is the plaintext; it
 * gets encrypted on the server before persistence and is never logged.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("settings")
public class SettingWriteRequest {

    /** Plaintext value; {@code null} clears the setting's value but keeps the record. */
    private @Nullable String value;

    @NotNull
    private SettingType type;

    private @Nullable String description;
}
