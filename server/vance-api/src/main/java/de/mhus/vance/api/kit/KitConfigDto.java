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
}
