package de.mhus.vance.api.kit;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * User-owned configuration for one installed kit, persisted as
 * {@code _vance/kits/config/<id>.yaml} beside — never inside — the
 * machine-generated install record {@code _vance/kits/installed/<id>.yaml}.
 *
 * <p>The split exists because of write ownership: the record is rewritten
 * in full on every kit update, while this document is edited by hand. In
 * one file, every update would overwrite user edits — exactly the problem
 * {@link KitPolicyDto} is meant to solve, one level up.
 *
 * <p>The document is optional. Absent, {@link KitPolicyDto#defaults()}
 * applies and the layer order follows {@code origin.installedAt}.
 *
 * <p>Spec: {@code planning/kit-installed-multi.md} §D10.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("kit")
public class KitConfigDto {

    /**
     * Layer order override; higher wins on artefact collisions between
     * kits. Null means "order by {@code origin.installedAt}", i.e. the
     * most recently installed kit wins.
     */
    private @Nullable Integer sortIndex;

    /**
     * Update behaviour for this kit's artefacts, or null when the user
     * has not expressed one.
     *
     * <p>Null is <b>not</b> the same as {@link KitPolicyDto#defaults()}:
     * "no opinion" lets the kit author's suggested policy apply, while an
     * explicit keep-with-no-rules overrides it. Defaulting this field
     * would silence every suggestion a kit ever ships.
     */
    private @Nullable KitPolicyDto policy;

    /**
     * Whether an update of this kit may replace a credential the project
     * already has. Absent means <b>no</b>.
     *
     * <h2>Why this is not {@link KitPolicyAction#OVERWRITE}</h2>
     * The policy decides by comparing hashes, and an encrypted value has
     * none — it cannot tell "the operator rotated this" from "unchanged
     * since we installed it". So {@code overwrite} in the policy would be
     * a guess, and the wrong guess resets a working key: an outage, not an
     * update. Worse, ODE sources already default to {@code overwrite} for
     * <em>documents</em>; folding credentials into the same word would turn
     * that default into "reset every credential on every update", which
     * nobody asked for.
     *
     * <p>Hence a separate switch, off unless somebody writes it down. What
     * it buys is the other direction: a host that rotates <em>its own</em>
     * key can get the new one to the projects that read it, which is
     * otherwise impossible — the revision moves, the kit updates, and the
     * project keeps a key that no longer opens anything.
     *
     * <h2>It only opens a gate</h2>
     * With it on, the {@code policy} rules still apply and can only narrow:
     * a rule matching the key with {@code keep} or {@code ignore} freezes
     * that one credential. With it off, no rule can replace a credential —
     * an {@code overwrite} rule written for a nearby setting key must not
     * reach a secret by accident.
     *
     * <h2>Not something a kit may ask for</h2>
     * Unlike {@code policy}, this has no counterpart in {@code kit.yaml}. A
     * kit declaring that it may overwrite credentials would be granting
     * itself the permission.
     */
    private @Nullable Boolean overwriteSecrets;
}
