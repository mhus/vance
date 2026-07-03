package de.mhus.vance.addon.brain.workbook.validate;

import de.mhus.vance.addon.brain.workpage.Block;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Validates a {@link Block.Button}: {@code buttonType} (v1: only
 * {@code script}), the {@code script} {@code .js} document (must exist), and a
 * present {@code title}.
 */
@Component
public class ButtonBlockValidator implements BlockValidator {

    @Override
    public boolean supports(Block block) {
        return block instanceof Block.Button;
    }

    @Override
    public List<Finding> validate(Block block, ValidationContext ctx) {
        Block.Button bt = (Block.Button) block;
        List<Finding> out = new ArrayList<>();
        if (!"script".equals(bt.buttonType())) {
            out.add(Finding.error(ctx.location(), "bad-type",
                    "`type: " + bt.buttonType() + "` is not supported — v1 only 'script'."));
        }
        Checks.scriptRef(out, ctx, "script", bt.script(), true);
        if (bt.title() == null || bt.title().isBlank()) {
            out.add(Finding.warning(ctx.location(), "missing-title",
                    "`title` is empty — the button has no label."));
        }
        return out;
    }
}
