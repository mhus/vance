/**
 * Runtime location of the current pod — its IP and port as seen by peers.
 *
 * <p>{@link de.mhus.vance.shared.location.LocationService} is the single entry
 * point; persisted records (e.g. {@code SessionDocument.boundPodIp}) call it
 * to stamp themselves with the owning pod.
 */
@NullMarked
package de.mhus.vance.shared.location;

import org.jspecify.annotations.NullMarked;
