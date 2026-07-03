package de.mhus.vance.addon.brain.workbook.validate;

import java.util.List;

/**
 * SPI for validating one {@code vance-<type>} fence block. One
 * implementation per block type, registered as a Spring {@code @Component};
 * {@link WorkbookValidationService} injects them all and dispatches by
 * {@link #supports(String)}. <b>Adding a new block type = add one
 * {@code @Component} here — no central switch to touch.</b>
 *
 * <p>Validators must be pure: they read only the {@link FenceBlock} and the
 * {@link ValidationContext} (reference facade), never mutate, and return
 * findings. Reference/existence checks go through {@code ctx.docs()} so the
 * validator is unit-testable with an in-memory {@link DocRefs}.
 */
public interface BlockValidator {

    /** The fence type this validator handles, e.g. {@code "form"}. */
    boolean supports(String fenceType);

    /** Check one fence; return zero or more findings. */
    List<Finding> validate(FenceBlock block, ValidationContext ctx);
}
