/**
 * Cross-project document access ({@code foreign_*} tool family).
 *
 * <p>Read-centric, tenant-internal access to documents of <em>other</em>
 * projects. Unlike the implicit {@code doc_*} tools these always take an
 * explicit, mandatory project id and never fall back to the caller's
 * current project. The security boundary is a per-target
 * {@link de.mhus.vance.shared.permission.PermissionService} check
 * ({@code READ} on the source, {@code CREATE}/{@code DELETE} on any
 * mutation target) — not the tool selection: a caller that is only
 * {@code READER} on a foreign project can read and copy-out but cannot
 * write into it. See {@code specification/public/foreign-document-access.md}.
 */
@org.jspecify.annotations.NullMarked
package de.mhus.vance.brain.tools.foreign;
