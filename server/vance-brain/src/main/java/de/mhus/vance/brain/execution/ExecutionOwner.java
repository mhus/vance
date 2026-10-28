package de.mhus.vance.brain.execution;

/**
 * Identifies which side runs a given execution: the brain pod itself, a
 * connected foot client (then {@link Foot#clientId} pins which one — multiple
 * foots can share a session), or the project's runner ({@link Runner}).
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

    /** A job on a runner ({@code planning/runner.md}); the brain asks it while the job runs. */
    record Runner(String runnerId) implements ExecutionOwner {
        @Override
        public String label() {
            return "runner:" + runnerId;
        }
    }
}
