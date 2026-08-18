/**
 * What the brain's database <em>is</em>: which migrations belong to it and
 * which application owns it.
 *
 * <p>A package of its own, and deliberately not a subpackage of
 * {@code schema} or {@code database} — those hold the machinery, and the
 * kit store scans them to reuse it. A declaration sitting under a scanned
 * machinery package would be picked up by every application that borrows
 * the mechanism, which is precisely the mixing this separation prevents.
 * Brain and anus scan {@code de.mhus.vance.shared} whole and both run
 * against this one database, so both find it.
 */
@org.jspecify.annotations.NullMarked
package de.mhus.vance.shared.braindb;
