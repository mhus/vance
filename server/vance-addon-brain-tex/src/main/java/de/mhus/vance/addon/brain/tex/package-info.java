/**
 * TeX / LaTeX addon for the Vance Brain — first-party, brain-only.
 *
 * <p>Bundles the {@code tex-compose} document kind
 * ({@link de.mhus.vance.addon.brain.tex.TexComposeKindHandler}), the
 * {@link de.mhus.vance.addon.brain.tex.TexService} orchestration, the
 * {@code tex2pdf} tool + REST controller, and the compile executors
 * ({@code local} via {@code latexmk}, {@code rbehzadan} via the external
 * tex2pdf service). Loaded by Spring Boot via
 * {@code META-INF/spring/.../AutoConfiguration.imports} pointing at
 * {@link de.mhus.vance.addon.brain.tex.TexAddon}.
 *
 * <p>The Brain Docker image still provides the TeX Live runtime
 * ({@code texlive-*} + {@code latexmk}); this addon owns everything
 * Java-side — see {@code planning/tex-addon-extraction.md}.
 */
@NullMarked
package de.mhus.vance.addon.brain.tex;

import org.jspecify.annotations.NullMarked;
