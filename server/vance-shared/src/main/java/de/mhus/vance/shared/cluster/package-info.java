/**
 * Brain-pod cluster registry. Each running brain pod owns a row in
 * {@code brain_pods} for the duration of its life cycle, refreshed on
 * a heartbeat tick. Other pods read from here to answer "which brains
 * are alive in this cluster, and which projects do they currently
 * own?". Manual project re-homing later layers on top of this; v1
 * is observational.
 *
 * <p>Colocated: document + package-private repository + service.
 */
@NullMarked
package de.mhus.vance.shared.cluster;

import org.jspecify.annotations.NullMarked;
