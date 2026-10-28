package de.mhus.vance.runner.protocol;

import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

/**
 * Header of a request, inside the age envelope. The payload (file contents, audio)
 * follows the header in the same stream and is not part of this record.
 *
 * @param v        protocol version, {@link RunnerEnvelope#VERSION}
 * @param token    the runner-bound JWT ({@link RunnerTokens})
 * @param replyKey the brain's one-time age recipient — the only key the answer is
 *                 encrypted to
 * @param op       the operation, e.g. {@code file.read}
 * @param args     the operation's arguments; absent = none
 */
public record RunnerRequest(
        int v,
        String token,
        String replyKey,
        String op,
        @Nullable JsonNode args) {

    public RunnerRequest {
        // Jackson reads a JSON null as a NullNode; "no arguments" is null here.
        args = args == null || args.isNull() ? null : args;
    }
}
