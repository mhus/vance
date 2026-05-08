/**
 * Spring-Shell command beans. {@link de.mhus.vance.anus.shell.AccessCommands}
 * exposes the auth lifecycle (login/logout/status/hash); domain CRUD lives in
 * sibling {@code *Commands} classes (TenantCommands, ProjectCommands, …).
 * The {@link de.mhus.vance.anus.shell.AnusExceptionResolver} renders
 * {@link de.mhus.vance.anus.access.NotAuthorizedException} as a single line
 * instead of a stack trace.
 */
@org.jspecify.annotations.NullMarked
package de.mhus.vance.anus.shell;
