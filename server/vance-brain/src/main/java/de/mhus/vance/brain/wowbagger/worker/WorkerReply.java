package de.mhus.vance.brain.wowbagger.worker;

import java.util.List;

/**
 * A worker's answer for one chunk: the output lines (validated by the pool —
 * exactly one per input record) plus what it cost, for the run counters.
 *
 * @param lines     raw output lines, in record order
 * @param calls     provider calls made for this chunk
 * @param tokensIn  input tokens reported, 0 when unknown
 * @param tokensOut output tokens reported, 0 when unknown
 */
public record WorkerReply(List<String> lines, long calls, long tokensIn, long tokensOut) {

    public WorkerReply {
        lines = List.copyOf(lines);
    }
}
