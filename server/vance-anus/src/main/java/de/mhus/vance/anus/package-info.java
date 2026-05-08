/**
 * Vance Anus — server-side admin shell. Spring-Shell-driven REPL with
 * password-gated CRUD over tenants, projects, users and teams.
 *
 * <p>Unlike {@code vance-foot} (which is a remote client and never touches
 * MongoDB), Anus runs against the same database the Brain uses and goes
 * through the {@code vance-shared} services so data-ownership rules stay
 * intact. The AI stack ({@code vance-brain}) is NOT on the classpath —
 * Anus boots fast and cannot accidentally start engines.
 *
 * <p>Module dependency boundary: {@code vance-shared} only (which transitively
 * pulls {@code vance-api}). Never {@code vance-brain}.
 */
@org.jspecify.annotations.NullMarked
package de.mhus.vance.anus;
