/**
 * Eddie-engine wire-format types.
 *
 * <p>Eddie-internal data structures (Worker-Link-Mirror, etc.) live in
 * {@code de.mhus.vance.shared.eddie}. This package only carries values
 * that travel over the WS — currently {@link ChannelMode}, used as a
 * tool-arg of {@code process_observe}.
 */
@NullMarked
package de.mhus.vance.api.eddie;

import org.jspecify.annotations.NullMarked;
