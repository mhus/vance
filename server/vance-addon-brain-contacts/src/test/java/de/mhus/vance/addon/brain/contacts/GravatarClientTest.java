package de.mhus.vance.addon.brain.contacts;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class GravatarClientTest {

    @Test
    void hashesTrimmedLowerCaseAddress() {
        String expected = "973dfe463ec85785f5f95af5ba3906eedb2d931c24e69824a89ea65dba4e813b";
        assertThat(GravatarClient.hash("test@example.com")).isEqualTo(expected);
        assertThat(GravatarClient.hash("  Test@Example.COM ")).isEqualTo(expected);
    }
}
