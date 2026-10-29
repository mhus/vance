package de.mhus.vance.shared.workspace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class GitCredentialKeyTest {

    @Test
    void theStorageKeyIsPrefixedUserAtHost_andParsesBack() {
        GitCredentialKey key = new GitCredentialKey("mhus", "GitHub.com");

        assertThat(key.storageKey()).isEqualTo("credential.git.mhus@github.com");
        assertThat(GitCredentialKey.parse("credential.git.mhus@github.com")).isEqualTo(key);
    }

    @Test
    void theLastAtSplits_soAnEmailLikeUserKeepsItsAt() {
        GitCredentialKey key = GitCredentialKey.parse("credential.git.me@corp.example@git.corp.example");

        assertThat(key.user()).isEqualTo("me@corp.example");
        assertThat(key.host()).isEqualTo("git.corp.example");
    }

    @Test
    void whatIsNotAGitCredentialKeyIsNotParsed() {
        for (String key : new String[] {
            "mhus@github.com",
            "credential.git.github.com",
            "credential.git.@github.com",
            "credential.git.mhus@",
            "git_mhus@github.com",
            "other_x@y"
        }) {
            assertThat(GitCredentialKey.parse(key)).as(key).isNull();
        }
        assertThatThrownBy(() -> new GitCredentialKey(" ", "github.com")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void hostAndAccountComeOutOfEveryUrlForm() {
        for (String url :
                new String[] {"https://github.com/o/r.git", "ssh://git@github.com/o/r.git", "git@github.com:o/r.git"}) {
            assertThat(GitCredentialKey.hostOf(url)).as(url).isEqualTo("github.com");
        }
        assertThat(GitCredentialKey.userOf("https://alice@github.com/o/r.git")).isEqualTo("alice");
        assertThat(GitCredentialKey.userOf("https://github.com/o/r.git")).isNull();
        assertThat(GitCredentialKey.hostOf("/srv/repos/r.git")).isNull();
    }
}
