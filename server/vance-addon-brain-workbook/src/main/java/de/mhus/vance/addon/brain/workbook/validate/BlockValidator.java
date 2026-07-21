package de.mhus.vance.addon.brain.workbook.validate;

import de.mhus.vance.addon.brain.workpage.Block;
import de.mhus.vance.shared.document.kind.validate.Finding;
import java.util.List;

/**
 * SPI for validating one {@link Block} of a parsed workpage. One
 * implementation per block type, registered as a Spring {@code @Component};
 * {@link WorkbookValidationService} injects them all and dispatches by
 * {@link #supports(Block)} (an {@code instanceof} on the sealed {@link Block}
 * type). <b>Adding a new block type = add one {@code @Component} here — no
 * central switch to touch.</b>
 *
 * <p>Validators consume the <em>canonical</em> {@link Block} model produced by
 * {@code WorkPageParser} (the single server-side fence parser) — no second
 * parser. They must be pure: read only the block + the {@link ValidationContext}
 * reference facade, never mutate, and return findings.
 */
public interface BlockValidator {

    /** Whether this validator handles the given block (usually an instanceof). */
    boolean supports(Block block);

    /** Check one block; return zero or more findings. */
    List<Finding> validate(Block block, ValidationContext ctx);
}
