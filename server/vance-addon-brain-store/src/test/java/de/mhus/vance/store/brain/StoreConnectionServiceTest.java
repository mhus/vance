package de.mhus.vance.store.brain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.api.kit.KitSourceDto;
import de.mhus.vance.api.kit.KitSourceType;
import de.mhus.vance.api.settings.SettingType;
import de.mhus.vance.shared.kit.KitException;
import de.mhus.vance.shared.settings.SettingService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Signing a brain user in to a store — spec:
 * {@code planning/kit-store.md} §3 S3, §7 Phase S3.
 */
@ExtendWith(MockitoExtension.class)
class StoreConnectionServiceTest {

    private static final String TENANT = "acme";
    private static final String USER = "marvin";
    private static final String ACCOUNT = "acc_7f3k9m2p4q";
    private static final String PROJECT = "research";
    private static final String LINK_TOKEN = "vst_secret-token";

    @Mock private StoreClient client;
    @Mock private SettingService settings;

    @InjectMocks private StoreConnectionService service;

    @Test
    void connect_keepsTheLinkTokenAndTheAccount() {
        givenLogin();

        StoreConnectionService.Connection connection =
                service.connect(TENANT, USER, source(), "buyer@example.com", "pw", "Laptop", "p1");

        assertThat(connection.accountId()).isEqualTo(ACCOUNT);
        // The token is a secret and goes through the encrypting path; the
        // account is not and is written in the clear beside it.
        verify(settings).setEncryptedSecret(
                eq(TENANT), eq(SettingService.SCOPE_PROJECT), eq("_user_" + USER),
                eq("store.token.vancetope-library"), eq(LINK_TOKEN), eq(SettingType.PASSWORD));
        verify(settings).setStringValue(
                eq(TENANT), eq(SettingService.SCOPE_PROJECT), eq("_user_" + USER),
                eq("store.account.vancetope-library"), eq(ACCOUNT));
    }

    @Test
    void connect_writesOntoTheUser_notTheProject() {
        // Where an Apple ID sits on a Mac: on the account that signed in,
        // not on the machine. The project is passed only as a label for the
        // store's device list.
        givenLogin();

        service.connect(TENANT, USER, source(), "buyer@example.com", "pw", null, "research");

        verify(settings, never()).setStringValue(
                any(), any(), eq("research"), any(), any());
    }

    @Test
    void connect_endsTheStoreSessionAfterwards() {
        // A brain is not a person; leaving a session open would be a live
        // credential for this account that nobody is holding on purpose.
        givenLogin();

        service.connect(TENANT, USER, source(), "buyer@example.com", "pw", null, null);

        verify(client).logout(any(), any());
    }

    @Test
    void connect_endsTheSessionEvenWhenLinkingFails() {
        when(client.login(any(), any(), any()))
                .thenReturn(new StoreClient.Session("vss_x", ACCOUNT, "buyer@example.com"));
        when(client.createLink(any(), any(), any(), any(), any()))
                .thenThrow(new KitException("the store said no"));

        assertThatThrownBy(() ->
                service.connect(TENANT, USER, source(), "buyer@example.com", "pw", null, null))
                .isInstanceOf(KitException.class);

        verify(client).logout(any(), any());
        verify(settings, never()).setEncryptedSecret(any(), any(), any(), any(), any(), any());
    }

    @Test
    void connect_withoutAUser_isRefused() {
        // There is nowhere to put a credential that belongs to nobody.
        givenLogin();

        assertThatThrownBy(() ->
                service.connect(TENANT, "  ", source(), "buyer@example.com", "pw", null, null))
                .isInstanceOf(KitException.class)
                .hasMessageContaining("needs a user");
    }

    @Test
    void disconnect_removesBothSettings() {
        service.disconnect(TENANT, USER, source());

        verify(settings).delete(TENANT, SettingService.SCOPE_PROJECT, "_user_" + USER,
                "store.token.vancetope-library");
        verify(settings).delete(TENANT, SettingService.SCOPE_PROJECT, "_user_" + USER,
                "store.account.vancetope-library");
    }

    @Test
    void disconnect_leavesTheLinkAtTheStoreAlone() {
        // Forgetting a credential here and deauthorising a machine remotely
        // are different acts. Doing the second as a silent side effect of
        // the first would be a surprise nobody asked for.
        service.disconnect(TENANT, USER, source());

        verify(client, never()).logout(any(), any());
    }

    @Test
    void connectionOf_readsTheAccountThroughTheCascade() {
        when(settings.getStringValueUserProjectCascade(
                TENANT, USER, PROJECT, null, "store.account.vancetope-library"))
                .thenReturn(ACCOUNT);

        assertThat(service.connectionOf(TENANT, USER, PROJECT, source()).isConnected()).isTrue();
    }

    @Test
    void connectionOf_seesAnAccountHeldOnTheProject() {
        // How a team shares one account. Reading without the project said
        // "not signed in" for exactly that setup, while installing and
        // reviewing on the same screen resolved the credential fine.
        when(settings.getStringValueUserProjectCascade(
                eq(TENANT), eq(USER), eq(PROJECT), any(), any()))
                .thenReturn(ACCOUNT);

        assertThat(service.connectionOf(TENANT, USER, PROJECT, source()).isConnected()).isTrue();
    }

    @Test
    void connectionOf_withoutASetting_isNotConnected() {
        when(settings.getStringValueUserProjectCascade(any(), any(), any(), any(), any()))
                .thenReturn(null);

        assertThat(service.connectionOf(TENANT, USER, PROJECT, source()).isConnected()).isFalse();
    }

    private void givenLogin() {
        when(client.login(any(), any(), any()))
                .thenReturn(new StoreClient.Session("vss_x", ACCOUNT, "buyer@example.com"));
        when(client.createLink(any(), any(), any(), any(), any()))
                .thenReturn(new StoreClient.IssuedLink("lnk_1", LINK_TOKEN));
    }

    private static KitSourceDto source() {
        return KitSourceDto.builder()
                .id("vancetope-library")
                .type(KitSourceType.LIBRARY)
                .url("https://library.vancetope.com")
                .build();
    }
}
