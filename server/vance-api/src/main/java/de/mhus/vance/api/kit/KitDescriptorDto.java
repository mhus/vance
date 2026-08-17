package de.mhus.vance.api.kit;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import java.time.Instant;
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

    /**
     * Who publishes this kit. Free text — a display name, not an
     * identity: nothing here is verified. What a kit's origin actually
     * <i>is</i> stays the source url it came from.
     */
    private @Nullable String vendor;

    /** SPDX identifier or free text, e.g. {@code MIT} or {@code proprietary}. */
    private @Nullable String license;

    /** Where to read more about the kit — docs, support, changelog. */
    private @Nullable String homepage;

    /**
     * Tenant this copy was licensed to. Written by the delivering shop,
     * never by the kit author.
     *
     * <p><b>Says nothing on its own.</b> Until a signature covers it
     * (see {@code planning/kit-shop.md} §5.3), the field is a claim
     * anyone can edit — it is carried so the signature has something to
     * bind to and so the UI can show a purchase, not so anything can be
     * enforced by its presence.
     */
    private @Nullable String licensedTo;

    /** Purchase this copy came from. Written by the shop — see {@link #licensedTo}. */
    private @Nullable String purchaseId;

    /**
     * When the licence stops entitling updates. Written by the shop.
     *
     * <p>Expiry blocks <b>updates</b>; what is installed keeps working.
     * Removing artefacts from a running system is a different class of
     * act and is not what a date in a metadata field decides.
     */
    private @Nullable Instant licenseExpiresAt;

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

    /**
     * {@code true} marks the kit as a tuning bundle (not a complete
     * setup). Such kits must not be tracked in {@code kit-manifest.yaml}
     * because update / export would later operate on an incomplete
     * base. {@code install} and {@code update} are rejected by the kit
     * service; only {@code apply} is allowed. Spec: kits.md §3.2.
     */
    @Builder.Default
    private boolean artifact = false;

    /**
     * {@code false} forbids direct import (install / update / apply) —
     * the kit is only usable when referenced as an entry in another
     * kit's {@code inherits:}. Use case: abstract base kits like
     * {@code base-arthur-prompts}. Spec: kits.md §3.2.
     */
    @Builder.Default
    private boolean installable = true;

    /**
     * {@code true} forbids other kits from inheriting this one (via
     * their {@code inherits:}). Use case: customer-specific end-product
     * configurations that should not serve as a base for further kits.
     * Direct import is unaffected. Spec: kits.md §3.2.
     */
    @Builder.Default
    private boolean sealed = false;

    /**
     * Update policy the kit's author <i>suggests</i> — e.g. "my
     * ai.alias.* settings are examples, never overwrite them".
     *
     * <p>Only a suggestion: it is never materialised into the project.
     * It sits between the built-in default and the user's own config,
     * so a kit can improve its recommendation in a later version for as
     * long as the user has not said anything of their own.
     */
    private @Nullable KitPolicyDto policy;
}
