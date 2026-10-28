package de.mhus.vance.brain.tools.runner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.mhus.vance.shared.net.SsrfGuard;
import de.mhus.vance.toolpack.ToolException;
import org.junit.jupiter.api.Test;

class RunnerRepoUrlTest {

    private final RunnerRepoUrl strict = new RunnerRepoUrl(false, url -> {});

    @Test
    void anHttpsUrlPassesAndYieldsItsHost() {
        assertThat(strict.check("https://GitHub.com/o/r.git")).isEqualTo("github.com");
    }

    @Test
    void localPathsAndOtherSchemesAreRefused() {
        for (String url : new String[] {
            "/runner/data/workspaces/acme/other/git-1",
            "file:///etc",
            "ssh://git@host/r.git",
            "ext::sh -c id",
            "git://h/r"
        }) {
            assertThatThrownBy(() -> strict.check(url)).isInstanceOf(ToolException.class);
        }
    }

    @Test
    void theEgressPolicyDecidesWhichHostsAreReachable() {
        RunnerRepoUrl guarded = new RunnerRepoUrl(false, url -> {
            throw new SsrfGuard.SsrfException("blocked " + url);
        });

        assertThatThrownBy(() -> guarded.check("https://10.0.0.5/r.git"))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("refused");
    }

    @Test
    void localSourcesNeedTheDevelopmentSwitch() {
        RunnerRepoUrl dev = new RunnerRepoUrl(true, url -> {});

        assertThat(dev.check("/tmp/repo")).isEmpty();
        assertThat(dev.check("file:///tmp/repo")).isEmpty();
    }
}
