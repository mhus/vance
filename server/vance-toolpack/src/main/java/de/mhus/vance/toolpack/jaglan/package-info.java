/**
 * Jaglan mount contract — the access side of foreign file sources (document
 * libraries, media a news source delivered alongside its items).
 *
 * <p>Same build as Zarniwoop and Centauri (protocol SPI, configured
 * instance, capabilities, project-scoped factory in brain), different
 * question: Zarniwoop asks "what is there on this topic", Centauri "what is
 * new", Jaglan "give me <em>these</em> bytes at <em>this</em> path". The
 * difference that matters is addressability — a path stays put and can be
 * handed to the document tooling, a search hit cannot.
 *
 * <p>This package holds the wire-neutral contract only: the
 * {@link de.mhus.vance.toolpack.jaglan.JaglanProtocol} SPI, the configured
 * {@link de.mhus.vance.toolpack.jaglan.JaglanInstance}, and its capabilities.
 * The dispatcher, the instance factory and the gate live in
 * {@code vance-brain} package {@code de.mhus.vance.brain.jaglan}. The data
 * records ({@code MountedStat}, {@code MountedSource}) sit in
 * {@code vance-api}, because they cross to {@code vance-shared} and that
 * module cannot see the toolpack.
 *
 * <p><b>Naming, and where it departs from Centauri.</b> Zarniwoop and
 * Centauri name their toolpack contract descriptively
 * ({@code toolpack.research}, {@code toolpack.feed}) and reserve the persona
 * for the brain-side dispatcher. Jaglan carries the persona on both sides,
 * because the descriptive word here would be {@code mount} — and unlike
 * "feed" or "research" that is not unambiguous in a Spring/Kubernetes tree,
 * where it also reads as filesystem mounts, volume mounts and workspace
 * mounts. {@code jaglan} is greppable and names exactly one thing; the same
 * argument that kept Centauri from being called {@code ac}.
 *
 * <p>The split that remains: <b>Jaglan</b> is the subsystem (protocol,
 * instance, capabilities, service, port), <b>Mounted*</b> is the domain
 * vocabulary for what a mounted document is ({@code MountedStat},
 * {@code MountedSource}, {@code MountAccess}, {@code isMounted}).
 *
 * <p>Independent of both: the {@code _ext/} path namespace and the
 * {@code ext_} id prefix are <b>data format</b>, not naming. They sit in
 * every stored path and every derived id, so they do not follow a rename —
 * see {@code JaglanPaths}.
 *
 * <p>A source never learns about the {@code _ext/<mount>/...} namespace: all
 * paths here are relative to the mount root.
 *
 * <p>{@code @NullMarked} package — references are non-null by default unless
 * annotated {@code @Nullable}. See CLAUDE.md "Null-Safety mit JSpecify".
 */
@NullMarked
package de.mhus.vance.toolpack.jaglan;

import org.jspecify.annotations.NullMarked;
