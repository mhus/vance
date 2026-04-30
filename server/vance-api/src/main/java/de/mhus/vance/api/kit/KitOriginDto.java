package de.mhus.vance.api.kit;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * Where the active kit was installed from. Lives inside
 * {@link KitManifestDto} and is the source-of-truth for the
 * {@code update} operation — {@code commit} is the installed SHA, the
 * {@code branch}/{@code path}/{@code url} triple addresses the same
 * remote.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("kit")
public class KitOriginDto {

    private String url;

    private @Nullable String path;

    private @Nullable String branch;

    /** SHA of the installed commit. Frozen at install time. */
    private @Nullable String commit;

    private @Nullable Instant installedAt;

    private @Nullable String installedBy;
}
