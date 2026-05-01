/**
 * File-transfer protocol DTOs. Bidirectional chunked file transfer
 * between Foot and Brain over WebSocket; see
 * {@code specification/file-transfer.md} for the full lifecycle.
 *
 * <p>Frame types: {@code transfer-init}, {@code transfer-init-response},
 * {@code transfer-chunk}, {@code transfer-complete},
 * {@code transfer-finish}, plus the upload-trigger
 * {@code client-file-upload-request}.
 */
@NullMarked
package de.mhus.vance.api.transfer;

import org.jspecify.annotations.NullMarked;
