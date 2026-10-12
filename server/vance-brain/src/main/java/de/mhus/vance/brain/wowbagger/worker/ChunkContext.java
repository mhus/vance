package de.mhus.vance.brain.wowbagger.worker;

/** Which chunk a worker is processing — for logs, errors and per-chunk metering. */
public record ChunkContext(int index, long startRecord) {}
