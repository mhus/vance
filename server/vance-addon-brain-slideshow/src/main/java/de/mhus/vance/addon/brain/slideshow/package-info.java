/**
 * Slideshow addon for the Vance Brain — first-party.
 *
 * <p>Bundles the Slideshow Vance Application (REST controller + view
 * rebuild service) and its {@code slideshow_*} server tools. Loaded
 * by Spring Boot via {@code META-INF/spring/.../AutoConfiguration.imports}
 * pointing at the addon's {@code @AutoConfiguration} entry class.
 *
 * <p>Compiles against {@code vance-brain} directly — there is no
 * thin addon-API layer in v1; flexibility outweighs the strict
 * boundary for first-party addons.
 */
@NullMarked
package de.mhus.vance.addon.brain.slideshow;

import org.jspecify.annotations.NullMarked;
