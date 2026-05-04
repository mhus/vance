package de.mhus.vance.api.profile;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * Body of {@code PUT /brain/{tenant}/profile/settings/{key}} — write
 * a single {@code webui.*} setting on the caller's own user-project.
 *
 * <p>Only string-typed settings are exposed via this endpoint; the
 * profile is not the place to set passwords or numeric tunables.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("profile")
public class ProfileSettingWriteRequest {

    private @Nullable String value;
}
