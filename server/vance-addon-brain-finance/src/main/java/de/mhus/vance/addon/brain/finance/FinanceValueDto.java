package de.mhus.vance.addon.brain.finance;

import de.mhus.vance.api.annotations.GenerateTypeScript;
import org.jspecify.annotations.Nullable;

/** Wire DTO for one value record. {@code mode} = {@code recurring} | {@code one_time}. */
@GenerateTypeScript("finance")
public record FinanceValueDto(
        double value,
        String mode,
        @Nullable FinancePeriodDto period,
        @Nullable String validFrom,
        @Nullable String validTo,
        @Nullable Integer sign,
        @Nullable FinanceInterestDto interest) {}
