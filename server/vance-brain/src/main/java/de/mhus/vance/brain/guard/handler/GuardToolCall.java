package de.mhus.vance.brain.guard.handler;

import java.util.Map;
import java.util.Objects;

/**
 * The exec-run tool call under judgment at the TOOL point — the tool
 * twin of {@code vance.guard.command}. {@code name} is the dispatched
 * tool name ({@code exec_run} or its {@code work_}/{@code client_}
 * backends), {@code args} the invocation params (for {@code exec_run}
 * the {@code command} string lives there).
 */
public record GuardToolCall(String name, Map<String, Object> args) {

    public GuardToolCall {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(args, "args");
        args = Map.copyOf(args);
    }
}
