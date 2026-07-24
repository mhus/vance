package de.mhus.vance.addon.brain.finance;

import de.mhus.vance.api.annotations.GenerateTypeScript;

/** Wire DTO for a value's interest pair. {@code basis} lowercase wire. */
@GenerateTypeScript("finance")
public record FinanceInterestDto(
        double rate,
        FinancePeriodDto period,
        String basis,
        boolean compound) {}
