/**
 * Wowbagger's worker SPI: what processes one chunk of records. The pool owns
 * claim, commit, retry, ledger and wakeups; a worker only turns N records into
 * N output lines. Workers are light — a synchronous call per chunk, never a
 * spawned think-process (planning/wowbagger-workers.md §2, §9).
 */
@NullMarked
package de.mhus.vance.brain.wowbagger.worker;

import org.jspecify.annotations.NullMarked;
