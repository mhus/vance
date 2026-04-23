/**
 * MongoDB-backed blob storage.
 *
 * <p>Files are split into configurable chunks (default 512 KB) and stored as
 * separate {@link de.mhus.vance.shared.storage.StorageData} documents sharing a
 * UUID. Reads stream one chunk at a time — memory usage stays at
 * {@code O(chunkSize)} for files of any size.
 *
 * <p>Public API is {@link de.mhus.vance.shared.storage.StorageService}; the
 * default implementation is
 * {@link de.mhus.vance.shared.storage.MongoStorageService}. Documents and
 * repositories are package-private and live next to the service.
 *
 * <p>Ported from the {@code nimbus} project with {@code worldId} swapped for
 * {@code tenantId}.
 */
@NullMarked
package de.mhus.vance.shared.storage;

import org.jspecify.annotations.NullMarked;
