package de.mhus.vance.addon.brain.centauri;

import de.mhus.vance.api.annotations.GenerateTypeScript;

/**
 * What became of a signal: `ACCEPTED`, `UNSUPPORTED` or `REJECTED`.
 *
 * <p>Note what is absent — any statement about effect. How the source weighs a
 * report is its business, so the UI says „reported", never „category changed".
 */
@GenerateTypeScript("centauri")
public record SignalResponseView(String outcome) {}
