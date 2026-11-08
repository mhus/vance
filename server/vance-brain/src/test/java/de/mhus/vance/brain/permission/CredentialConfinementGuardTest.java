package de.mhus.vance.brain.permission;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Keeps the credential confinement from being dropped on the tool path.
 *
 * <p>A tool decides what its caller may do from the {@code ToolInvocationContext}. That context carries the
 * project a pinned credential is confined to; {@code forToolSubject(ctx)} and {@code writeActor(ctx, path)} fold it
 * into the subject, the {@code (tenantId, userId)} forms do not. Building the subject from {@code ctx.userId()}
 * alone is therefore the one way to silently widen a confined call, and this test forbids it.
 *
 * <p>It is a ratchet: {@code credential-confinement-pending.txt} lists what is still to migrate, and may only
 * shrink. A listed file that no longer offends has to be removed from the list, so the list cannot rot.
 *
 * <p>Acting for somebody other than the caller (an inbox owner, a decider) legitimately uses the two-argument
 * form with that person's name; those calls do not read {@code ctx.userId()} and are not matched.
 */
class CredentialConfinementGuardTest {

    private static final Pattern DROPS_CONFINEMENT = Pattern.compile(
            "(forToolSubject|writeActor)\\(\\s*(?:ctx|context)\\.tenantId\\(\\)\\s*,\\s*(?:ctx|context)\\.userId\\(\\)");

    @Test
    void no_tool_builds_its_permission_subject_from_the_user_alone_except_what_is_still_pending() throws IOException {
        Path server = serverRoot();
        Set<String> offenders = offenders(server);
        Set<String> pending = pending();

        Set<String> newOffenders = new TreeSet<>(offenders);
        newOffenders.removeAll(pending);
        Set<String> fixed = new TreeSet<>(pending);
        fixed.removeAll(offenders);

        assertThat(newOffenders)
                .as("use forToolSubject(ctx) / writeActor(ctx, path) so the credential confinement is kept")
                .isEmpty();
        assertThat(fixed)
                .as("these files no longer drop the confinement — delete them from credential-confinement-pending.txt")
                .isEmpty();
    }

    private static Set<String> offenders(Path server) throws IOException {
        Set<String> out = new TreeSet<>();
        try (Stream<Path> modules = Files.list(server)) {
            for (Path module : modules.filter(Files::isDirectory).toList()) {
                Path main = module.resolve("src/main/java");
                if (!Files.isDirectory(main)) continue;
                try (Stream<Path> files = Files.walk(main)) {
                    for (Path file :
                            files.filter(f -> f.toString().endsWith(".java")).toList()) {
                        if (DROPS_CONFINEMENT
                                .matcher(Files.readString(file, StandardCharsets.UTF_8))
                                .find()) {
                            out.add(server.relativize(file).toString());
                        }
                    }
                }
            }
        }
        return out;
    }

    private static Set<String> pending() throws IOException {
        try (var in = CredentialConfinementGuardTest.class.getResourceAsStream("/credential-confinement-pending.txt")) {
            if (in == null) throw new IllegalStateException("credential-confinement-pending.txt is missing");
            return new String(in.readAllBytes(), StandardCharsets.UTF_8)
                    .lines()
                    .map(String::trim)
                    .filter(l -> !l.isEmpty() && !l.startsWith("#"))
                    .collect(Collectors.toCollection(TreeSet::new));
        }
    }

    /** The reactor root: the directory above {@code vance-brain}, whichever way the test is started. */
    private static Path serverRoot() {
        Path cwd = Path.of("").toAbsolutePath();
        Path candidate = cwd.getFileName().toString().equals("vance-brain") ? cwd.getParent() : cwd;
        if (!Files.isDirectory(candidate.resolve("vance-brain"))) {
            throw new UncheckedIOException(new IOException("Cannot locate the server reactor from " + cwd));
        }
        return candidate;
    }
}
