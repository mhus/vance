/**
 * One-shot {@code --setup} wizard mode for Anus.
 *
 * <p>{@link de.mhus.vance.anus.setup.SetupBootstrap} parses argv before
 * Spring Boot starts. {@link de.mhus.vance.anus.setup.SetupShellRunner}
 * takes over the Spring Shell boot sequence when the flag was found and
 * delegates to {@link de.mhus.vance.anus.setup.SetupWizard}, which guides
 * the operator through tenant + user + AI-provider provisioning.
 *
 * <p>Layered intentionally as a sibling of {@code de.mhus.vance.anus.sudo}
 * — the same pattern (static flag-holder + dedicated runner) without
 * coupling either mode to the other. Both runners are mutually exclusive
 * at argv level: {@code --setup} ignores {@code --sudo} commands and vice
 * versa.
 */
@org.jspecify.annotations.NullMarked
package de.mhus.vance.anus.setup;
