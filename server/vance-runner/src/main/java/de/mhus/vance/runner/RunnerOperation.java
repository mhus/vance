package de.mhus.vance.runner;

import de.mhus.vance.runner.protocol.RunnerTokens;
import java.io.InputStream;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * One thing a runner can do for a call: {@code ping}, {@code info}, later files, exec
 * jobs, mechanics. The server has already opened the envelope and verified the token
 * by the time {@link #execute} runs.
 *
 * <p>An operation that takes a body must <b>read it completely before it commits
 * anything</b>: age only proves a stream intact at its end, so a write that acts on
 * the first bytes could keep half a file. Whatever an operation leaves unread, the
 * server drains and checks.
 */
public interface RunnerOperation {

    /** The {@code op} this handles, e.g. {@code file.read}. */
    String name();

    OperationResult execute(Call call) throws OperationException;

    /** What an operation is given. */
    record Call(RunnerTokens.Claims claims, @Nullable JsonNode args, InputStream body) {}

    /** What it returns: a JSON result and, optionally, a body to stream. */
    record OperationResult(
            @Nullable JsonNode result, @Nullable InputStream body) {

        public static OperationResult of(@Nullable Object dto) {
            return new OperationResult(dto == null ? null : JsonMapper.shared().valueToTree(dto), null);
        }

        public static OperationResult of(@Nullable Object dto, InputStream body) {
            return new OperationResult(dto == null ? null : JsonMapper.shared().valueToTree(dto), body);
        }
    }

    /** A failed operation with a stable {@code code} for programs and a message for people. */
    class OperationException extends Exception {

        private final String code;

        public OperationException(String code, String message) {
            super(message);
            this.code = code;
        }

        public OperationException(String code, String message, Throwable cause) {
            super(message, cause);
            this.code = code;
        }

        public String code() {
            return code;
        }
    }
}
