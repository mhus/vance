package de.mhus.vance.addon.brain.finance;

import de.mhus.vance.api.annotations.GenerateTypeScript;

/** Wire DTO for a {@code period} — {@code count × unit} (unit lowercase wire). */
@GenerateTypeScript("finance")
public record FinancePeriodDto(int count, String unit) {}
