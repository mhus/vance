/**
 * File-transfer subsystem on the Brain side. State machine for both
 * inbound uploads (Foot → Brain workspace) and outbound downloads
 * (Brain workspace → Foot disk), driven by the brain LLM tools
 * {@code transfer_client_to_work} and {@code transfer_work_to_client}.
 *
 * <p>Spec: {@code specification/file-transfer.md}.
 */
@NullMarked
package de.mhus.vance.brain.transfer;

import org.jspecify.annotations.NullMarked;
