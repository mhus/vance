package de.mhus.vance.brain.execution;

/**
 * Identifies which side runs a given execution. Either the brain pod
 * itself or a connected foot client (then {@link Foot#clientId} pins
 * which one — multiple foots can share a session).
 */
public sealed interface ExecutionOwner {

    /** Compact "brain" / "foot:<clientId>" string for logs and tool output. */
    String label();

    record Brain() implements ExecutionOwner {
        public static final Brain INSTANCE = new Brain();

        @Override
        public String label() {
            return "brain";
        }
    }

    record Foot(String clientId) implements ExecutionOwner {
        @Override
        public String label() {
            return "foot:" + clientId;
        }
    }
}
