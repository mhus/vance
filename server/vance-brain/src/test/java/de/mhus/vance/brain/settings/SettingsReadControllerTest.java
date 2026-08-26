package de.mhus.vance.brain.settings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.mhus.vance.brain.permission.RequestAuthority;
import de.mhus.vance.shared.settings.SettingService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * The general settings read for web clients.
 *
 * <p>What is <em>not</em> tested here, because it is not this class's rule:
 * that a secret stays unreadable. {@link SettingService} refuses encrypted
 * types on every generic read, so this controller has no type check of its own
 * — deliberately, so that whoever adds a setting type later cannot forget this
 * call site. Testing it here would pin a guard that does not exist.
 */
class SettingsReadControllerTest {

    private SettingService settings;
    private SettingsReadController controller;
    private HttpServletRequest request;

    @BeforeEach
    void setUp() {
        settings = mock(SettingService.class);
        controller = new SettingsReadController(settings, mock(RequestAuthority.class));
        request = mock(HttpServletRequest.class);
    }

    @Test
    void read_resolvesNamedKeysThroughTheCascade() {
        when(settings.getStringValueCascade("acme", "p", null, "a.b")).thenReturn("1");
        when(settings.getStringValueCascade("acme", "p", null, "c.d")).thenReturn("2");

        assertThat(controller.read("acme", "p", "a.b, c.d", null, request))
                .containsExactly(Map.entry("a.b", "1"), Map.entry("c.d", "2"));
    }

    @Test
    void read_omitsWhatIsNotSetRatherThanReturningNull() {
        // A caller iterating the answer should not have to tell "present as
        // null" from "absent". An encrypted setting lands here too — the
        // service returns null for it — and looking exactly like an unset key
        // is the point: saying "exists, but not for you" would confirm a piece
        // of the tenant's configuration.
        when(settings.getStringValueCascade("acme", "p", null, "gone")).thenReturn(null);

        assertThat(controller.read("acme", "p", "gone", null, request)).isEmpty();
    }

    @Test
    void read_takesAPrefixFamily() {
        when(settings.findByPrefixCascade("acme", "p", null, "myapp."))
                .thenReturn(Map.of("myapp.size", "20"));

        assertThat(controller.read("acme", "p", null, "myapp.", request))
                .containsEntry("myapp.size", "20");
    }

    @Test
    void read_needsExactlyOneOfKeysOrPrefix() {
        // Neither would be "give me everything", which is a scan; both has no
        // obvious meaning, and picking one would make the answer depend on
        // which the reader believed won.
        for (String[] pair : new String[][] {{null, null}, {"a", "b."}, {"  ", null}}) {
            assertThatThrownBy(() -> controller.read("acme", "p", pair[0], pair[1], request))
                    .isInstanceOf(ResponseStatusException.class)
                    .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                    .isEqualTo(HttpStatus.BAD_REQUEST);
        }
    }

    @Test
    void read_refusesAnAbsurdNumberOfKeys() {
        String many = String.join(",", java.util.Collections.nCopies(51, "k"));

        assertThatThrownBy(() -> controller.read("acme", "p", many, null, request))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Use 'prefix'");
    }

    @Test
    void read_ignoresEmptyEntriesInTheKeyList() {
        when(settings.getStringValueCascade("acme", "p", null, "a")).thenReturn("1");

        assertThat(controller.read("acme", "p", "a,,  ,", null, request))
                .containsExactly(Map.entry("a", "1"));
    }
}
