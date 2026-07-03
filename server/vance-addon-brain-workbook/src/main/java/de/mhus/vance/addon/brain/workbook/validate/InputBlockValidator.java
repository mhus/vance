package de.mhus.vance.addon.brain.workbook.validate;

import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Validates a {@code vance-input} fence: the {@code config} text document
 * (must exist), the optional {@code saveScript}, and the {@code multiline} /
 * {@code session} booleans. The bound file is treated verbatim, so no kind
 * constraint is enforced on {@code config}.
 */
@Component
public class InputBlockValidator implements BlockValidator {

    @Override
    public boolean supports(String fenceType) {
        return "input".equals(fenceType);
    }

    @Override
    public List<Finding> validate(FenceBlock b, ValidationContext ctx) {
        List<Finding> out = new ArrayList<>();
        Checks.docRef(out, b, ctx, "config", true, null);
        Checks.scriptRef(out, b, ctx, "saveScript", false);
        Checks.boolAttr(out, b, "multiline");
        Checks.boolAttr(out, b, "session");
        return out;
    }
}
