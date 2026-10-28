/**
 * Running programs for a tenant, without Spring: {@link de.mhus.vance.exec.ProcessSpec}
 * and the {@link de.mhus.vance.exec.ProcessLauncher} contract, the sealed child
 * environment ({@link de.mhus.vance.exec.ExecEnvPolicy}), isolation argv and prlimit
 * caps ({@link de.mhus.vance.exec.ProcessIsolation}), per-call scratch directories
 * ({@link de.mhus.vance.exec.ScratchArea}) and program probes. Shared by the brain
 * and the runner ({@code planning/multi-tenant-isolation.md} phase 2f,
 * {@code planning/runner.md}).
 */
@NullMarked
package de.mhus.vance.exec;

import org.jspecify.annotations.NullMarked;
