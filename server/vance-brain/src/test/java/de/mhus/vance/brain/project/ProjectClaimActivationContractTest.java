package de.mhus.vance.brain.project;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Pins the invariant "a lease taken is a project activated".
 *
 * <h2>The failure this prevents</h2>
 * <p>{@code ProjectManagerService.claimForLocalPod} /
 * {@code claimForLocalPodOrRedirect} make this pod the <em>owner</em> of a
 * project. They do not put it into {@link ProjectActivationRegistry}. Since the
 * project-ownership lease rework the two document listeners that keep a running
 * project running — {@code UrsaHookDocumentListener} and
 * {@code UrsaSchedulerDocumentListener} — are activation-gated, while
 * {@code DocumentChangeRouter} delivers a change to the <em>writing</em> pod
 * regardless of who holds the lease. A project that is claimed but never
 * brought therefore owns its hooks and schedulers and runs none of them, and
 * no other pod picks them up either.
 *
 * <p>The damage is entirely silent: no exception, no warning, no failing
 * request. It surfaces only as "my hook never fires", weeks later. Five
 * production call sites had drifted into exactly this shape before an
 * integration test happened to trip over one of them
 * ({@code HookTriggerPipelineE2ETest}).
 *
 * <h2>Why a source scan and not a behavioural test</h2>
 * <p>This is a rule about <em>every future</em> call site, which is precisely
 * what a behavioural test cannot express: mocking a handler proves that
 * <em>this</em> handler brings, and says nothing about the sixth entry point
 * somebody adds next month — the mechanism by which all five previous ones
 * appeared. Same reasoning, and the same shape, as
 * {@code SettingTypeEncryptedContractTest#noProductionSourceComparesAgainstAProtectionConstant}.
 *
 * <p>Granularity is deliberately per file, not per method: a tripwire that
 * forces a human to look at a new claim, not a proof that the {@code bring}
 * sits in the right branch. Being coarse is what keeps it from breaking on
 * ordinary refactors.
 */
class ProjectClaimActivationContractTest {

    /** A call to either lease primitive — the receiver's name does not matter. */
    private static final Pattern CLAIM_CALL = Pattern.compile(
            "\\.claimForLocalPod(?:OrRedirect)?\\s*\\(");

    /**
     * A {@code <receiver>.bring(} call, whichever field or provider holds the
     * service. Requiring the dot rather than a bare {@code bring(} is the
     * second of two defences against prose: this very file, and the javadoc on
     * every site fixed alongside it, talks about "bring()" in plain English,
     * and a word-boundary pattern would happily accept that as the fix.
     * Comments and string literals are removed before matching (see
     * {@link #stripCommentsAndStrings}) — that is the first defence, and it is
     * the load-bearing one. Verified by
     * {@link #aClaimWithoutABringIsDetected()}, because a guard that cannot
     * fail is not a guard.
     */
    private static final Pattern BRING_CALL = Pattern.compile("\\.\\s*bring\\s*\\(");

    /**
     * <ul>
     *   <li>{@code ProjectManagerService} declares both primitives and is the
     *       one place allowed to hand out a bare lease.</li>
     *   <li>{@code ProjectLifecycleService} <em>is</em> the activation: its
     *       {@code bring} claims first and then registers. Requiring it to call
     *       itself would be circular.</li>
     *   <li>{@code ProjectService} (vance-shared) only names the method in an
     *       exception message; it cannot call it — the method lives one module
     *       up in vance-brain.</li>
     * </ul>
     */
    private static final Set<String> EXEMPT_FILES = Set.of(
            "ProjectManagerService.java",
            "ProjectLifecycleService.java",
            "ProjectService.java");

    @Test
    void everyProductionClaimIsFollowedByABring() {
        Path serverRoot = serverRoot();
        List<Path> sourceRoots = mainJavaRoots(serverRoot);
        assertThat(sourceRoots)
                .as("source roots found below %s", serverRoot)
                .isNotEmpty();

        List<String> offenders = new ArrayList<>();
        for (Path root : sourceRoots) {
            try (Stream<Path> files = Files.walk(root)) {
                files.filter(p -> p.getFileName().toString().endsWith(".java"))
                        .filter(p -> !EXEMPT_FILES.contains(p.getFileName().toString()))
                        .forEach(p -> {
                            String src = stripCommentsAndStrings(read(p));
                            if (CLAIM_CALL.matcher(src).find()
                                    && !BRING_CALL.matcher(src).find()) {
                                offenders.add(serverRoot.relativize(p).toString());
                            }
                        });
            } catch (IOException e) {
                throw new UncheckedIOException("cannot scan " + root, e);
            }
        }

        assertThat(offenders)
                .as("these take a project lease without ever bringing the project: "
                        + "the pod owns its hooks and schedulers and runs none of them, "
                        + "silently. Call ProjectLifecycleService.bring(...) — it claims "
                        + "through the same primitive and then activates, is idempotent, "
                        + "and short-circuits to a lease refresh once the project runs here")
                .isEmpty();
    }

    /**
     * A guard on the guard: if the scan finds no claim at all, the regex has
     * rotted (method renamed) and the test above would pass vacuously forever.
     */
    @Test
    void theScanActuallySeesClaimCallSites() {
        Path serverRoot = serverRoot();
        List<String> withClaims = new ArrayList<>();
        for (Path root : mainJavaRoots(serverRoot)) {
            try (Stream<Path> files = Files.walk(root)) {
                files.filter(p -> p.getFileName().toString().endsWith(".java"))
                        .filter(p -> CLAIM_CALL.matcher(
                                stripCommentsAndStrings(read(p))).find())
                        .forEach(p -> withClaims.add(p.getFileName().toString()));
            } catch (IOException e) {
                throw new UncheckedIOException("cannot scan " + root, e);
            }
        }
        assertThat(withClaims)
                .as("no claim call site found — the primitive was renamed and "
                        + "everyProductionClaimIsFollowedByABring now passes vacuously")
                .isNotEmpty();
    }

    /**
     * The detector, applied to a synthetic offender and to the real fixed
     * shape. Without this, the first version of the scan silently accepted
     * {@code SessionResumeHandler} while the {@code bring} call was deleted —
     * the javadoc explaining the fix contained the words "bring()" and that
     * was enough to satisfy a bare word-boundary pattern.
     */
    @Test
    void aClaimWithoutABringIsDetected() {
        String claimOnly = """
                /** Documented: the caller must bring() the project afterwards. */
                class X {
                    void f() {
                        projectManager.claimForLocalPod(t, p);
                        log.info("remember to call lifecycleService.bring(t, p)");
                    }
                }
                """;
        String claimAndBring = """
                class X {
                    void f() {
                        projectManager.claimForLocalPodOrRedirect(t, p);
                        lifecycleService.bring(t, p);
                    }
                }
                """;

        assertThat(violates(claimOnly))
                .as("a bare claim must be flagged even when comments and log "
                        + "messages talk about bring()")
                .isTrue();
        assertThat(violates(claimAndBring))
                .as("claim followed by a real bring call is the compliant shape")
                .isFalse();
    }

    private static boolean violates(String source) {
        String code = stripCommentsAndStrings(source);
        return CLAIM_CALL.matcher(code).find() && !BRING_CALL.matcher(code).find();
    }

    /**
     * Blanks out block comments, line comments, string literals, text blocks
     * and char literals so only executable code is matched. Deliberately a
     * scanner and not a parser: it never has to produce valid Java, only to
     * stop prose from counting as a call.
     */
    static String stripCommentsAndStrings(String src) {
        StringBuilder out = new StringBuilder(src.length());
        int i = 0;
        int n = src.length();
        while (i < n) {
            char c = src.charAt(i);
            if (c == '/' && i + 1 < n && src.charAt(i + 1) == '/') {
                while (i < n && src.charAt(i) != '\n') i++;
            } else if (c == '/' && i + 1 < n && src.charAt(i + 1) == '*') {
                i += 2;
                while (i + 1 < n && !(src.charAt(i) == '*' && src.charAt(i + 1) == '/')) i++;
                i = Math.min(i + 2, n);
            } else if (c == '"' && src.startsWith("\"\"\"", i)) {
                i += 3;
                while (i < n && !src.startsWith("\"\"\"", i)) i++;
                i = Math.min(i + 3, n);
            } else if (c == '"' || c == '\'') {
                char quote = c;
                i++;
                while (i < n && src.charAt(i) != quote) {
                    if (src.charAt(i) == '\\') i++;
                    i++;
                }
                i++;
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }

    /**
     * Every {@code <module>/src/main/java} below the aggregator. Test sources
     * are deliberately not scanned — a test may legitimately exercise the raw
     * lease primitive.
     */
    private static List<Path> mainJavaRoots(Path serverRoot) {
        try (Stream<Path> paths = Files.walk(serverRoot, 5)) {
            return paths.filter(Files::isDirectory)
                    .filter(p -> p.endsWith(Path.of("src", "main", "java")))
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException("cannot list modules of " + serverRoot, e);
        }
    }

    /** The Maven aggregator directory holding all server modules. */
    private static Path serverRoot() {
        Path dir = Path.of("").toAbsolutePath();
        while (dir != null) {
            if (Files.isDirectory(dir.resolve("vance-api"))
                    && Files.isDirectory(dir.resolve("vance-shared"))
                    && Files.isDirectory(dir.resolve("vance-brain"))) {
                return dir;
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException(
                "server root not found above " + Path.of("").toAbsolutePath());
    }

    private static String read(Path p) {
        try {
            return Files.readString(p, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + p, e);
        }
    }
}
