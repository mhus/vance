/**
 * Prak — brain-side stages of the memory-evaluation pipeline.
 *
 * <p>Prak (Hitchhiker character — "Prak speaks the truth") classifies
 * conversation spans for the memory-evaluation pipeline. This package
 * holds:
 * <ul>
 *   <li>{@link de.mhus.vance.brain.prak.PrakService} — LLM-driven
 *       analyzer, wraps {@code LightLlmService} with the
 *       {@code _prak} recipe and maps the JSON reply onto the wire
 *       records in {@code de.mhus.vance.shared.prak}.</li>
 *   <li>{@link de.mhus.vance.brain.prak.PrakSanitizer} — deterministic
 *       post-processor: cross-item supersede, evidence validation,
 *       confidence floor, token-Jaccard dedup, hard-cap downgrade,
 *       coverage telemetry.</li>
 *   <li>{@link de.mhus.vance.brain.prak.HotPathMarkerDetector} +
 *       {@link de.mhus.vance.brain.prak.CheapPathFilter} — the
 *       sync trigger gate and Java pre-filter that decide
 *       <em>whether</em> the analyzer runs at all.</li>
 * </ul>
 *
 * <p>The strength-deriver, promotion service and remaining trigger
 * orchestration arrive in later phases — see
 * {@code planning/memory-evaluation-pipeline.md} §12 for the staged
 * order.
 */
@NullMarked
package de.mhus.vance.brain.prak;

import org.jspecify.annotations.NullMarked;
