package de.mhus.vance.api.megadodo;

import de.mhus.vance.api.annotations.GenerateTypeScript;

/**
 * What a feed row points at. The Web-UI hard-wires one link target per
 * value — deliberately, instead of carrying a generic URI: a row is
 * written by the server, and where a session is best shown is a decision
 * of whoever builds the view, not of whoever emits the event.
 *
 * <p>The matching identifier lives in {@code refId}. Adding a value here
 * without teaching the UI about it is harmless — an unknown type simply
 * renders without a link.
 */
@GenerateTypeScript("megadodo")
public enum MegadodoRefType {

    PROJECT,
    SESSION,
    PROCESS,
    USER,
    TOOL,
    SCHEDULER,
    HOOK,
    EVENT,
    DOCUMENT
}
