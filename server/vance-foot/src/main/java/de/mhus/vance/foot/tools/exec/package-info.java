/**
 * Client-side shell execution. {@code client_exec_run /
 * client_exec_status / client_exec_kill} expose a local
 * {@link de.mhus.vance.foot.tools.exec.ClientExecutorService} to the
 * brain over the WebSocket tool channel — the LLM can drive shell
 * commands on the user's actual machine.
 *
 * <p>Mirrors the brain-side {@code de.mhus.vance.brain.tools.exec}
 * package in shape: one job index, virtual-thread workers, line-by-line
 * pump into per-job log files, id-polling for long jobs.
 */
@NullMarked
package de.mhus.vance.foot.tools.exec;

import org.jspecify.annotations.NullMarked;
