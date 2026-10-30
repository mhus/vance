package de.mhus.vance.brain.resources;

/** One file of a bundled resource area; {@code path} is relative to {@code _vance/<section>/}. */
public record BundledResourceFile(String path, String content) {}
