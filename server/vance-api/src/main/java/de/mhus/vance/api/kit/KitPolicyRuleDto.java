package de.mhus.vance.api.kit;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * One line of a {@link KitPolicyDto} rule list: a glob over exactly one
 * artefact namespace plus the action to apply when it matches.
 *
 * <p>Exactly one of {@link #document} / {@link #setting} is set —
 * document paths and setting keys are separate namespaces and a single
 * shared pattern space would force the kit repo's transport layout
 * ({@code settings/<key>.yaml}) into user-authored config.
 *
 * <p>Server-tool configs are ordinary documents under
 * {@code server-tools/<name>.yaml}, so they are addressed via
 * {@link #document} — there is deliberately no {@code tool} key.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("kit")
public class KitPolicyRuleDto {

    /** Glob over document paths, relative to the project document root. */
    private @Nullable String document;

    /** Glob over project-scoped setting keys. */
    private @Nullable String setting;

    /** Action applied when this rule matches. */
    private KitPolicyAction action;
}
