package de.mhus.vance.api.kit;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * One place kits may come from, as configured in
 * {@code _vance/config/kit-sources.yaml} of the {@code _tenant} project.
 *
 * <p>A source is matched to a kit by url prefix — the kit reference
 * itself stays a plain {@code (url, path)} pair, so nothing about kit
 * identity changes when a source is added or removed. What the source
 * adds is everything that cannot be read off a url: which loader
 * applies, whether signatures are required, and which key verifies them.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("kit")
public class KitSourceDto {

    /** Stable handle for logs and messages. Not part of any kit's identity. */
    private String id;

    private KitSourceType type;

    /**
     * Url this source covers. Matched as a prefix, longest match wins —
     * so a single repository can be configured more strictly than the
     * host it lives on.
     */
    private String url;

    @Builder.Default
    private KitSignaturePolicy signature = KitSignaturePolicy.OFF;

    /**
     * PEM-encoded public key that verifies this source's signatures.
     * Null falls back to the key bundled for the default library — which
     * is only meaningful for that one, so any other source enforcing
     * signatures has to bring its own.
     */
    private @Nullable String publicKey;

    /**
     * Where this library's <b>store front</b> answers, when it differs
     * from {@link #url}.
     *
     * <p>Delivery and store are two processes (see
     * {@code planning/kit-store.md} §3 S4) but they normally sit behind
     * one hostname on different path prefixes — {@code /library/…} and
     * {@code /store/…} — so this stays null for almost every
     * configuration. Null means "the same host as the library".
     */
    private @Nullable String storeUrl;
}
