package de.mhus.vance.addon.brain.workbook.validate;

import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Validates a {@code vance-button} fence: {@code type} (v1: only
 * {@code script}), the {@code script} {@code .js} document (must exist), and
 * a present {@code title}.
 */
@Component
public class ButtonBlockValidator implements BlockValidator {

    @Override
    public boolean supports(String fenceType) {
        return "button".equals(fenceType);
    }

    @Override
    public List<Finding> validate(FenceBlock b, ValidationContext ctx) {
        List<Finding> out = new ArrayList<>();
        String type = b.str("type");
        if (type == null) {
            out.add(Finding.error(b.location(), "missing-type",
                    "`type` is required (v1: 'script')."));
        } else if (!"script".equals(type)) {
            out.add(Finding.error(b.location(), "bad-type",
                    "`type: " + type + "` is not supported — v1 only 'script'."));
        }
        Checks.scriptRef(out, b, ctx, "script", true);
        if (b.str("title") == null) {
            out.add(Finding.warning(b.location(), "missing-title",
                    "`title` is empty — the button has no label."));
        }
        return out;
    }
}
