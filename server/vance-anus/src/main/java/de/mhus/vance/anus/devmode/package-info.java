/**
 * Local-only developer escape hatch. When
 * {@code vance.anus.dev-mode.enabled=true}, additional shell commands
 * become available that expose otherwise-masked data (currently:
 * {@code setting show-password} prints PASSWORD plaintext). The
 * {@link de.mhus.vance.anus.devmode.DevModeBootCheck} refuses to start
 * the application when the flag is true while a {@code prod} /
 * {@code production} Spring profile is active.
 *
 * <p>Off by default. Intended for local debugging only — never enable
 * on shared or production deployments.
 */
@org.jspecify.annotations.NullMarked
package de.mhus.vance.anus.devmode;
