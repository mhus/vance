package de.mhus.vance.runner;

/**
 * A token the brain hands the runner for one {@code git} operation. Lives in memory for
 * that call only: it is never written to a RootDir descriptor, the repository's config
 * or a log, and it reaches {@code git} through the child's environment, not its argv.
 */
record GitCredentials(String username, String token) {

    @Override
    public String toString() {
        return "GitCredentials[username=" + username + ", token=***]";
    }
}
