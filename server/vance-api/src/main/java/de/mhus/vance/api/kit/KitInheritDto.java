package de.mhus.vance.api.kit;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * Reference to one inherit-layer in a kit's {@code kit.yaml}. The same
 * shape is used as the source-spec for {@code install}/{@code update}
 * operations — see {@link KitImportRequestDto}.
 *
 * <p>{@code url} accepts {@code https://...}, {@code file:///...} and
 * absolute filesystem paths. {@code path} is the sub-directory inside
 * the repo (default: repo root). {@code branch} defaults to {@code main}
 * when blank. {@code commit} pins a specific SHA — when set, it wins
 * over {@code branch}.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("kit")
public class KitInheritDto {

    private String url;

    private @Nullable String path;

    private @Nullable String branch;

    private @Nullable String commit;
}
