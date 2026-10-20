package de.mhus.vance.shared.project;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Smoke-tests the {@link VanceCallerLocator} stack walker. The frame name
 * comes out of {@link StackWalker}, which behaves the same on every JVM, so
 * we only verify the two invariants that matter for the diagnostic in
 * {@link ProjectService#transitionStatus}:
 *
 * <ol>
 *   <li>When called from a Vance class, the helper returns a frame whose
 *       class name starts with {@code de.mhus.vance.} — i.e. it does not
 *       accidentally report the helper itself or a JDK frame.</li>
 *   <li>Calling with a {@code skip} that steps past every Vance frame
 *       yields {@code null} rather than throwing.</li>
 *   <li>The filter that excludes the helper does not accidentally exclude
 *       sibling classes with the same prefix (e.g. a test class named
 *       {@code VanceCallerLocatorTest}).</li>
 * </ol>
 */
class VanceCallerLocatorTest {

    @Test
    void calledFromAVanceFrame_returnsThatFrame() {
        String frame = VanceCallerLocator.firstVanceFrame(1);

        assertThat(frame).isNotNull();
        // The test method itself is the first Vance frame above the helper.
        assertThat(frame).startsWith("de.mhus.vance.");
        assertThat(frame).contains("VanceCallerLocatorTest.calledFromAVanceFrame_returnsThatFrame");
    }

    @Test
    void skippingPastAllVanceFrames_returnsNull() {
        // skip=999 walks past helper + this test method + every JUnit /
        // Surefire reflection frame injected on the way — well past any
        // Vance code. The contract is "return null rather than throw".
        String frame = VanceCallerLocator.firstVanceFrame(999);

        assertThat(frame).isNull();
    }

    @Test
    void skipZeroOrNegative_isClampedToOne() {
        // skip=0 would report the helper itself; we clamp to 1 so the
        // helper stays invisible to its own diagnostic.
        String frame = VanceCallerLocator.firstVanceFrame(0);

        assertThat(frame).isNotNull();
        assertThat(frame).startsWith("de.mhus.vance.");
        assertThat(frame).doesNotContain("VanceCallerLocator.firstVanceFrame");
    }
}
