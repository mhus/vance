/**
 * The brain ↔ runner wire protocol, without a network: {@link
 * de.mhus.vance.runner.protocol.RunnerEnvelope} seals and opens the age-encrypted
 * request and its request-bound answer, {@link
 * de.mhus.vance.runner.protocol.RunnerTokens} verifies the runner-bound JWT carried
 * inside. See {@code planning/runner.md} §4.
 */
@NullMarked
package de.mhus.vance.runner.protocol;

import org.jspecify.annotations.NullMarked;
