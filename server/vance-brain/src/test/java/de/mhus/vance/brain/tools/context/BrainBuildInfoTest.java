package de.mhus.vance.brain.tools.context;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;

/**
 * Parsing of the git-commit-id {@code git.properties} layouts and the
 * {@link BrainBuildInfo} value object. Loading goes through the
 * production constructor with a stub {@link ResourceLoader} — the same
 * single-constructor path Spring takes, no second constructor to drift
 * out of sync.
 */
class BrainBuildInfoTest {

    @Test
    void parse_readsCurrentPluginKeyLayout() {
        Properties props = new Properties();
        props.setProperty("git.commit.id", "abc123");
        props.setProperty("git.branch", "main");
        props.setProperty("git.dirty", "true");

        BrainBuildInfo.GitFacts facts = BrainBuildInfo.parseGitProperties(props);

        assertThat(facts.commit()).isEqualTo("abc123");
        assertThat(facts.branch()).isEqualTo("main");
        assertThat(facts.dirty()).isTrue();
    }

    @Test
    void parse_acceptsLegacyFullKeyLayout() {
        Properties props = new Properties();
        props.setProperty("git.commit.id.full", "legacy789");
        props.setProperty("git.branch", "main");
        props.setProperty("git.dirty", "false");

        BrainBuildInfo.GitFacts facts = BrainBuildInfo.parseGitProperties(props);

        assertThat(facts.commit()).isEqualTo("legacy789");
        assertThat(facts.dirty()).isFalse();
    }

    @Test
    void parse_withoutCommitOrBranch_reportsUnknown() {
        Properties props = new Properties();
        props.setProperty("git.build.time", "2026-09-09T10:00:00Z");

        assertThat(BrainBuildInfo.parseGitProperties(props)).isEqualTo(BrainBuildInfo.GitFacts.UNKNOWN);
    }

    @Test
    void parse_commitOnly_fillsBranchWithUnknown() {
        Properties props = new Properties();
        props.setProperty("git.commit.id", "abc123");

        BrainBuildInfo.GitFacts facts = BrainBuildInfo.parseGitProperties(props);

        assertThat(facts.commit()).isEqualTo("abc123");
        assertThat(facts.branch()).isEqualTo("unknown");
        assertThat(facts.dirty()).isFalse();
    }

    @Test
    void constructor_stampedResource_carriesTheGitFacts() {
        BrainBuildInfo info = new BrainBuildInfo(
                "0.4.0-SNAPSHOT", "2026-09-09T10:00:00Z", loaderReturning(stamp("abc123", "main", "true")));

        assertThat(info.version()).isEqualTo("0.4.0-SNAPSHOT");
        assertThat(info.buildTime()).isEqualTo("2026-09-09T10:00:00Z");
        assertThat(info.git().commit()).isEqualTo("abc123");
        assertThat(info.git().branch()).isEqualTo("main");
        assertThat(info.git().dirty()).isTrue();
    }

    @Test
    void constructor_unstampedBuild_reportsUnknownNotFailure() {
        // ByteArrayResource of a file without commit/branch keys — the
        // plugin may stamp a file that carries nothing usable.
        BrainBuildInfo info = new BrainBuildInfo("0.4.0-SNAPSHOT", "t", loaderReturning(stamp(null, null, null)));

        assertThat(info.git()).isEqualTo(BrainBuildInfo.GitFacts.UNKNOWN);
    }

    @Test
    void constructor_unreadableStamp_reportsUnknownNotFailure() {
        BrainBuildInfo info = new BrainBuildInfo("0.4.0-SNAPSHOT", "t", loaderReturning(new BrokenResource()));

        assertThat(info.git()).isEqualTo(BrainBuildInfo.GitFacts.UNKNOWN);
    }

    // ──────────────────── helpers ────────────────────

    /** ByteArrayResource that fails on read — exercises the IOException fallback. */
    private static class BrokenResource extends ByteArrayResource {

        BrokenResource() {
            super(new byte[0]);
        }

        @Override
        public InputStream getInputStream() throws IOException {
            throw new IOException("boom");
        }
    }

    private static ResourceLoader loaderReturning(Resource resource) {
        return new ResourceLoader() {
            @Override
            public Resource getResource(String location) {
                return resource;
            }

            @Override
            public ClassLoader getClassLoader() {
                return BrainBuildInfoTest.class.getClassLoader();
            }
        };
    }

    /** A git.properties-shaped stamp; null keys are simply left out. */
    private static Resource stamp(String commit, String branch, String dirty) {
        StringBuilder body = new StringBuilder();
        if (commit != null) {
            body.append("git.commit.id=").append(commit).append('\n');
        }
        if (branch != null) {
            body.append("git.branch=").append(branch).append('\n');
        }
        if (dirty != null) {
            body.append("git.dirty=").append(dirty).append('\n');
        }
        return new ByteArrayResource(body.toString().getBytes(StandardCharsets.UTF_8));
    }
}
