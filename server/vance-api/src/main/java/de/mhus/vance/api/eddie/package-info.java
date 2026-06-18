/**
 * Eddie-engine wire-format types.
 *
 * <p>Worker-Link bookkeeping lives in
 * {@code de.mhus.vance.shared.thinkprocess.WorkerLinkSnapshot} (used
 * by Eddie + Arthur). This package only carries values that travel
 * over the WS — currently {@link ChannelMode}, used as a tool-arg of
 * {@code process_observe}.
 */
@NullMarked
package de.mhus.vance.api.eddie;

import org.jspecify.annotations.NullMarked;
