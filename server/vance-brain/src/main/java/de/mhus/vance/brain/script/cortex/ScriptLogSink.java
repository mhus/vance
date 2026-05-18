package de.mhus.vance.brain.script.cortex;

/**
 * Sink for one line of script-emitted output. The implementation
 * decides what to do — push to WebSocket, buffer for status polling,
 * persist for audit — Script Cortex wires it to the WS sender.
 */
@FunctionalInterface
public interface ScriptLogSink {

    /**
     * @param stream {@code log}, {@code info}, {@code warn}, {@code error}
     * @param line   already formatted; never null. May contain newlines
     *               when the script's argument toString did.
     */
    void accept(String stream, String line);
}
