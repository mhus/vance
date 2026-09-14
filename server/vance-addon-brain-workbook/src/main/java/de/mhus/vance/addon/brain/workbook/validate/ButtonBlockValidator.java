package de.mhus.vance.addon.brain.workbook.validate;

import de.mhus.vance.addon.brain.workbook.action.WorkbookButtonService;
import de.mhus.vance.addon.brain.workpage.Block;
import de.mhus.vance.shared.document.kind.validate.Finding;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Validates a {@link Block.Button}: {@code buttonType} must be a
 * registered {@code ButtonActionHandler} type (the {@code script}
 * reference check applies to {@code type: script} only), plus a present
 * {@code title}.
 */
@Component
public class ButtonBlockValidator implements BlockValidator {

    private final WorkbookButtonService buttonService;

    public ButtonBlockValidator(WorkbookButtonService buttonService) {
        this.buttonService = buttonService;
    }

    @Override
    public boolean supports(Block block) {
        return block instanceof Block.Button;
    }

    @Override
    public List<Finding> validate(Block block, ValidationContext ctx) {
        Block.Button bt = (Block.Button) block;
        List<Finding> out = new ArrayList<>();
        if (!buttonService.types().contains(bt.buttonType())) {
            out.add(Finding.error(
                    ctx.location(),
                    "bad-type",
                    "`type: " + bt.buttonType() + "` is not a registered action — registered types: "
                            + buttonService.types() + "."));
        }
        if ("script".equals(bt.buttonType())) {
            Checks.scriptRef(out, ctx, "script", bt.script(), true);
        }
        if (bt.title() == null || bt.title().isBlank()) {
            out.add(Finding.warning(ctx.location(), "missing-title", "`title` is empty — the button has no label."));
        }
        return out;
    }
}
