package de.mhus.vance.brain.kit;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.mhus.vance.shared.kit.KitException;
import org.junit.jupiter.api.Test;

/** Uses literal IPs only, so no DNS lookup decides the outcome. */
class KitSourceUrlPolicyTest {

    @Test
    void check_publicHttpsRemote_isAllowed() {
        assertThatCode(() -> new KitSourceUrlPolicy(false).check("https://1.1.1.1/kits.git"))
                .doesNotThrowAnyException();
    }

    @Test
    void check_remoteOnInternalAddress_isRefused() {
        assertThatThrownBy(() -> new KitSourceUrlPolicy(true).check("https://10.42.40.23/kits.git"))
                .isInstanceOf(KitException.class);
    }

    @Test
    void check_nonHttpRemote_isRefused() {
        KitSourceUrlPolicy policy = new KitSourceUrlPolicy(true);

        assertThatThrownBy(() -> policy.check("ssh://git@1.1.1.1/kits.git")).isInstanceOf(KitException.class);
        assertThatThrownBy(() -> policy.check("git@1.1.1.1:kits.git")).isInstanceOf(KitException.class);
        assertThatThrownBy(() -> policy.check("git://1.1.1.1/kits.git")).isInstanceOf(KitException.class);
        assertThatThrownBy(() -> policy.check("relative/repo")).isInstanceOf(KitException.class);
    }

    @Test
    void check_localFolder_followsTheOperatorSwitch() {
        assertThatCode(() -> new KitSourceUrlPolicy(true).check("file:///srv/kits"))
                .doesNotThrowAnyException();
        assertThatCode(() -> new KitSourceUrlPolicy(true).check("/srv/kits")).doesNotThrowAnyException();
        assertThatThrownBy(() -> new KitSourceUrlPolicy(false).check("file:///srv/kits"))
                .isInstanceOf(KitException.class);
        assertThatThrownBy(() -> new KitSourceUrlPolicy(false).check("/etc")).isInstanceOf(KitException.class);
    }
}
