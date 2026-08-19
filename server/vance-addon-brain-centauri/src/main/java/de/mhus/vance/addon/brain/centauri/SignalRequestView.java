package de.mhus.vance.addon.brain.centauri;

import de.mhus.vance.api.annotations.GenerateTypeScript;
import org.jspecify.annotations.Nullable;

/**
 * A back-channel signal from the reader's UI.
 *
 * <p>Closed vocabulary on the wire too: `signal` is `REPORT` or `REQUEST`, and
 * the argument belonging to it is mandatory. A free-form verb field would be the
 * RPC tunnel the whole design avoids.
 *
 * <p>{@code note} leaves Vancetope towards a foreign organisation — the UI says
 * so at the field, and the length is capped server-side.
 */
@GenerateTypeScript("centauri")
public record SignalRequestView(
        String sourceId,
        String itemId,
        String signal,
        @Nullable String reason,
        @Nullable String requestKind,
        @Nullable String note) {}
