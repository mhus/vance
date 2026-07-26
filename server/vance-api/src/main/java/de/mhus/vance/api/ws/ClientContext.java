package de.mhus.vance.api.ws;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * Ephemeral, per-connection description of the client at the other end of
 * a {@code CLIENT} work-target connection (today: the {@code vance-foot}
 * CLI). Sent once on the WebSocket handshake via the
 * {@link HandshakeHeaders#CLIENT_CONTEXT} header, JSON-encoded, and parsed
 * into the brain's {@code ConnectionContext}. Purely informational — it
 * lets the engine tell the LLM which platform and shell its
 * {@code client_exec_run} / {@code client_file_*} calls actually run on,
 * so the model targets the right command dialect (POSIX {@code sh} vs.
 * Windows {@code cmd.exe}) instead of defaulting to bash.
 *
 * <p><b>Ephemeral, not a setting.</b> This is connection-bound state that
 * changes per launch (cwd, sandbox toggle) and is absent for headless
 * turns (scheduler / auto-wakeup) — the opposite of a persisted user
 * preference. The user's display timezone, by contrast, is a persistent
 * setting ({@code display.timezone}) resolved server-side even when no
 * client is connected; {@link #timezone} here is only the client
 * machine's zone carried alongside for future use and is <em>not</em> the
 * source of truth for date rendering.
 *
 * <p>A missing or unparseable header must never break the handshake — the
 * brain treats it as "no client context" and simply omits the environment
 * prompt block.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("ws")
public class ClientContext {

    /**
     * Normalised OS family: {@code "windows"}, {@code "macos"},
     * {@code "linux"} or {@code "unknown"}. Normalised on the client so
     * the brain never re-parses {@code os.name} quirks.
     */
    private @Nullable String os;

    /** Raw JVM {@code os.arch} (e.g. {@code "aarch64"}, {@code "amd64"}). */
    private @Nullable String arch;

    /**
     * The shell {@code client_exec_run} actually invokes on this client —
     * {@code "/bin/sh"} on POSIX, {@code "cmd.exe"} on Windows. Kept in
     * sync with the client executor's own shell selection; it is the
     * command dialect the LLM should target, not the user's interactive
     * login shell.
     */
    private @Nullable String shell;

    /** Absolute working directory the client process was launched in. */
    private @Nullable String cwd;

    /**
     * Whether the client's file/exec sandbox is active. When {@code false}
     * (e.g. {@code --no-sandbox}) the LLM's client-side calls are not
     * gated by the local permission policy.
     */
    private boolean sandboxEnabled;

    /**
     * The client machine's IANA timezone id (e.g. {@code "Europe/Berlin"}).
     * Carried for future use — date rendering uses the persistent
     * {@code display.timezone} setting, not this field.
     */
    private @Nullable String timezone;
}
