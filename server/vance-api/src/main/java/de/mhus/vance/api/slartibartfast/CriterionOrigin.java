package de.mhus.vance.api.slartibartfast;

/**
 * Where a {@link Criterion} came from. Drives the CONFIRMING-phase
 * decision — only criteria with origin in the {@code INFERRED_*}
 * family AND with confidence below the recipe's
 * {@code confirmationThreshold} get an inbox-confirmation request;
 * {@link #USER_STATED} and high-confidence {@code INFERRED_*}
 * pass through into {@code acceptanceCriteria} directly.
 *
 * <p>The split exists because the failure modes are symmetric:
 * inferring too little misses obvious user expectations
 * ("write me an essay" implies "save it"); asking too much turns
 * the planner into a clarification gauntlet. Origin lets the
 * planner err on neither side — it inferes broadly but surfaces
 * weak inferences for review.
 */
public enum CriterionOrigin {
    /** Lifted directly from the user's request — wording or
     *  unambiguous paraphrase. Confidence is implicitly 1.0. */
    USER_STATED,

    /** Engine derived from a generally-applicable convention
     *  ("written content gets persisted as a document",
     *  "responses match the user's language"). High-confidence
     *  defaults — bypass user confirmation by default. */
    INFERRED_CONVENTION,

    /** Engine derived from kit-specific or domain-specific
     *  patterns visible in available evidence ("Adams essays in
     *  this kit have been 3-5 chapters"). Confidence usually
     *  middle-ground — borderline cases land in CONFIRMING. */
    INFERRED_DOMAIN,

    /** Engine derived from contextual signals around the request
     *  ("user wrote in German → output in German"). High when
     *  the signal is unambiguous, falls otherwise. */
    INFERRED_CONTEXT,

    /** An {@code INFERRED_*}-origin criterion that the user
     *  explicitly confirmed during CONFIRMING. Treated as
     *  authoritative as {@link #USER_STATED} from this point on. */
    USER_CONFIRMED,

    /** Engine fallback when nothing else fits — last-resort
     *  guess. Always low-confidence; almost always lands in
     *  CONFIRMING or gets dropped. */
    DEFAULT,
}
