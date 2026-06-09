/**
 * Agent-facing tools for the UrsaEvents subsystem — {@code event_list},
 * {@code event_get}, {@code event_set}, {@code event_delete} for
 * managing event YAML documents, plus {@code event_fire} for
 * end-to-end verification (bearer-token check bypassed because the
 * engine is already trust-checked through the tenant/project gate).
 * See {@code specification/events.md}.
 */
@NullMarked
package de.mhus.vance.brain.tools.ursaevent;

import org.jspecify.annotations.NullMarked;
