/**
 * Foundation for the "Vance application" pattern — a folder turns
 * into a self-contained app by carrying an {@code _app.yaml}
 * manifest at its root. {@link VanceApplication} is the contract;
 * one implementation per app type sits beside its domain tools
 * (e.g. {@link CalendarsApplication} for {@code app: calendar}).
 *
 * <p>The {@link VanceApplicationRegistry} routes the generic
 * {@code app_rebuild} tool — and future generic Web-UI refresh
 * buttons — to the right service without knowing the domain
 * details.
 *
 * <p>Spec: {@code specification/doc-kind-application.md}.
 */
@NullMarked
package de.mhus.vance.brain.applications;

import org.jspecify.annotations.NullMarked;
