/**
 * One configuration document per external source instance.
 *
 * <p>Zarniwoop search endpoints, Centauri feed endpoints and Jaglan mounts all
 * describe the same shape of thing — a named instance of a protocol, with a
 * base URL, an optional credential and an on/off switch — so they share one
 * record and one loader instead of three copies of it.
 *
 * <p>They used to be settings ({@code research.endpoint.<id>.*} and friends).
 * The move to documents is in {@code planning/source-config-documents.md}; the
 * short version is that the instance id sat in the setting <em>key</em>, which
 * no form engine can render, and that a flat string namespace has nowhere to
 * put a list, a nested block or a comment.
 */
@org.jspecify.annotations.NullMarked
package de.mhus.vance.brain.sourceconfig;
