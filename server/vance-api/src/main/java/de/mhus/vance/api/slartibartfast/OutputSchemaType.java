package de.mhus.vance.api.slartibartfast;

/**
 * Which kind of plan-artifact the Slartibartfast run is asked to
 * produce. Determines the validator wired into VALIDATING and the
 * shape of the YAML written in PERSISTING.
 *
 * <p>See {@code specification/slartibartfast-engine.md} §4.
 */
public enum OutputSchemaType {
    /** Vogon strategy YAML (phases, gates, scorers, postActions).
     *  Validated by {@code StrategyResolver.parseStrategy}. */
    VOGON_STRATEGY,

    /** Marvin recipe YAML — engineParams (allowedSubTaskRecipes
     *  etc.) plus promptPrefix. Statically validated for param
     *  shape; the runtime re-prompt loop in
     *  {@code MarvinEngine.validatePlanChildren} enforces it
     *  during the actual run. */
    MARVIN_RECIPE,
}
