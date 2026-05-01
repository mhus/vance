/**
 * File-transfer subsystem on the Foot side. Provides a sandboxed
 * workspace under {@code vance.foot.workspace.root} (default
 * {@code ~/.vance/foot/}) into which inbound transfers from the Brain
 * land, and out of which outbound uploads to the Brain are read.
 *
 * <p>Spec: {@code specification/file-transfer.md}.
 */
@NullMarked
package de.mhus.vance.foot.transfer;

import org.jspecify.annotations.NullMarked;
