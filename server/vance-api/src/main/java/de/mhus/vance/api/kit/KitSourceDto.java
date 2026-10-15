package de.mhus.vance.api.kit;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import java.util.Map;
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
     * Keys this source signs with, by {@code keyId} — the id a kit's
     * {@code kit.sig.yaml} names — mapped to the PEM-encoded public key.
     *
     * <p>A ring rather than one key, so a source can rotate: during the
     * switch the old and the next key are both trusted, and a kit signed
     * with either verifies. A kit names its key, and only that key is
     * tried.
     *
     * <p>Null means "no ring configured". Only an entry whose {@code url}
     * is the default library's ({@code https://library.vancetope.com},
     * compared ignoring case and trailing slashes) then gets the keys
     * bundled with the release; every other source has no keys at all, and
     * a signed kit from it fails verification as {@code NO_KEY} (refused
     * under {@code required}, let through and marked under {@code warn}).
     * A ring that is present replaces the bundled keys
     * entirely — also for the default library. An empty map is not a way
     * to say "none": the parser refuses it.
     */
    private @Nullable Map<String, String> publicKeys;

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

    /**
     * What a person calls this store, if the id is not that already.
     *
     * <p>The id is a handle — short, url-safe, used in configuration. A
     * title is what belongs on a screen where somebody is picking a kit:
     * they choose by the name of the place, not by its address.
     */
    private @Nullable String title;
}
