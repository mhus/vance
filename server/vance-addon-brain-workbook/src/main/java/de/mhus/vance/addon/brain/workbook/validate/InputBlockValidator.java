package de.mhus.vance.addon.brain.workbook.validate;

import de.mhus.vance.addon.brain.workpage.Block;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Validates a {@link Block.Input}: the {@code data} text document (must exist)
 * and the optional {@code saveScript}. {@code multiline}/{@code session} are
 * typed booleans in the model, so no attribute check is needed here. The bound
 * file is treated verbatim → no kind constraint on {@code data}.
 */
@Component
public class InputBlockValidator implements BlockValidator {

    @Override
    public boolean supports(Block block) {
        return block instanceof Block.Input;
    }

    @Override
    public List<Finding> validate(Block block, ValidationContext ctx) {
        Block.Input in = (Block.Input) block;
        List<Finding> out = new ArrayList<>();
        Checks.docRef(out, ctx, "data", in.data(), true, null);
        Checks.scriptRef(out, ctx, "saveScript", in.saveScript(), false);
        return out;
    }
}
