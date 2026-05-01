/**
 * File-transfer subsystem on the Brain side. State machine for both
 * inbound uploads (Foot → Brain workspace) and outbound downloads
 * (Brain workspace → Foot disk), driven by the brain LLM tools
 * {@code client_file_upload} and {@code client_file_download}.
 *
 * <p>Spec: {@code specification/file-transfer.md}.
 */
@NullMarked
package de.mhus.vance.brain.transfer;

import org.jspecify.annotations.NullMarked;
