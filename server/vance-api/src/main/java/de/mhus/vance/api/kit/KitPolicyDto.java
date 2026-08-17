package de.mhus.vance.api.kit;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import java.util.ArrayList;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * How updates of one installed kit treat artefacts that already exist:
 * a default action plus an ordered list of glob exceptions.
 *
 * <p>Evaluation is <b>last match wins</b>, like {@code .gitignore} —
 * the list reads top-down as "default, then exceptions, then exceptions
 * to the exception".
 *
 * <p>Lives in the per-kit config document
 * {@code _vance/kits/config/<id>.yaml}, never in the install record —
 * the record is machine-generated and rewritten on every update, this
 * is hand-written and must survive untouched.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("kit")
public class KitPolicyDto {

    /** Action for artefacts no rule matches. */
    @Builder.Default
    private KitPolicyAction defaultAction = KitPolicyAction.KEEP;

    /** Ordered exceptions; the last matching entry wins. */
    @Builder.Default
    private List<KitPolicyRuleDto> rules = new ArrayList<>();

    /** The policy that applies when a kit has no config document at all. */
    public static KitPolicyDto defaults() {
        return KitPolicyDto.builder().build();
    }
}
