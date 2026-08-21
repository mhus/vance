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

    /**
     * What the source said, at install time, about the content it was
     * about to hand over — combined with the parameters it was asked for.
     *
     * <p>Exists so a periodic check can ask „would that source give me
     * something different now?" without downloading anything. Compared as
     * an opaque token: null means the question cannot be answered for this
     * kit and nothing is checked, which is honest — guessing would either
     * refetch on every tick or never.
     *
     * <p><b>One field rather than two</b> (revision and params-hash apart)
     * because nothing needs the halves separately. A source's revision
     * cannot reflect the parameters it never saw — those go to the build,
     * not to the cacheable capabilities call — so the parameters have to be
     * folded in on this side, and once folded there is one question and one
     * answer. {@link #commit} still carries the human-readable stamp.
     */
    private @Nullable String provisioningStamp;
}
