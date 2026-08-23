package de.mhus.vance.api.kit;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import java.time.Instant;
import java.util.Map;
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

    /**
     * What the source was asked for when this kit was fetched — the
     * {@code params:} of the provisioning entry, verbatim.
     *
     * <p>Recorded because it cannot be reconstructed from anywhere else and
     * an update needs it. A manual {@code kit update} rebuilds the request
     * from this record; without the parameters it asked a host that assembles
     * per request for the <em>default</em> variant, and a project silently
     * lost its equipment ("the German build with the invoicing module" became
     * the plain one). The stamp next to it has the same problem and the same
     * answer.
     *
     * <p><b>Never a resolved secret.</b> Parameters are deliberately not
     * secret-resolved anywhere in the chain — a {@code {{secret:…}}} written
     * here goes to a third party as the reference it is, and the credential
     * for that party is the token, which is a separate field and is
     * <em>not</em> recorded. So this holds exactly the text the provisioning
     * document holds, in a document under the same reserved {@code _vance/}
     * namespace: nothing is exposed that was not already. If parameter
     * resolution is ever introduced, the resolved value must not reach this
     * field.
     *
     * <p>{@code null} means "none were asked for" — a hand-typed install, or
     * a provisioning entry without a {@code params:} block. Written afresh on
     * every update, like every other part of the record, so removing the
     * block from {@code provisioning.yaml} does take effect.
     */
    private @Nullable Map<String, Object> params;
}
