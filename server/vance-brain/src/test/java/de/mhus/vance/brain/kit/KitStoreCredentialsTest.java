package de.mhus.vance.brain.kit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.api.kit.KitSourceDto;
import de.mhus.vance.api.kit.KitSourceType;
import de.mhus.vance.shared.kit.KitException;
import de.mhus.vance.shared.settings.SettingService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Where the store credential comes from — spec:
 * {@code planning/kit-store.md} §3 S3.
 */
@ExtendWith(MockitoExtension.class)
class KitStoreCredentialsTest {

    private static final String TENANT = "acme";
    private static final String PROJECT = "research";
    private static final String USER = "marvin";
    private static final String LIBRARY_URL = "https://library.vancetope.com";

    @Mock private KitSourceRegistry sources;
    @Mock private SettingService settings;

    @InjectMocks private KitStoreCredentials credentials;

    @Test
    void libraryWithoutExplicitToken_readsBothSettings() {
        givenSource(KitSourceType.LIBRARY);
        when(settings.getStringValueUserProjectCascade(
                TENANT, USER, PROJECT, null, "store.account.vancetope-library"))
                .thenReturn("acc_7f3k9m2p4q");
        when(settings.getDecryptedPasswordUserProjectCascade(
                TENANT, USER, PROJECT, null, "store.token.vancetope-library"))
                .thenReturn("vst_secret");

        KitAccess access = credentials.resolve(TENANT, PROJECT, USER, LIBRARY_URL, null);

        assertThat(access.tenantId()).isEqualTo(TENANT);
        assertThat(access.storeAccount()).isEqualTo("acc_7f3k9m2p4q");
        assertThat(access.token()).isEqualTo("vst_secret");
    }

    @Test
    void explicitToken_winsOverTheConfiguredOne() {
        // Someone linking a brain for the first time has a token before they
        // have a setting to keep it in.
        givenSource(KitSourceType.LIBRARY);
        when(settings.getStringValueUserProjectCascade(any(), any(), any(), any(), any()))
                .thenReturn("acc_7f3k9m2p4q");

        KitAccess access = credentials.resolve(TENANT, PROJECT, USER, LIBRARY_URL, "vst_typed");

        assertThat(access.token()).isEqualTo("vst_typed");
        verify(settings, never())
                .getDecryptedPasswordUserProjectCascade(any(), any(), any(), any(), any());
    }

    @Test
    void gitSource_doesNotBorrowTheStoreToken() {
        // A store.* setting standing in as the credential for a git clone
        // would be a surprising thing for the name to mean — and it would
        // send a library bearer token to an unrelated host.
        givenSource(KitSourceType.GIT);

        KitAccess access = credentials.resolve(
                TENANT, PROJECT, USER, "https://github.com/acme/kits.git", null);

        assertThat(access.token()).isNull();
        verify(settings, never())
                .getDecryptedPasswordUserProjectCascade(any(), any(), any(), any(), any());
    }

    @Test
    void gitSource_stillReportsTheLinkedAccount() {
        // The licence gate runs for every source, so the account has to be
        // known even where no store credential applies.
        givenSource(KitSourceType.GIT);
        when(settings.getStringValueUserProjectCascade(any(), any(), any(), any(), any()))
                .thenReturn("acc_7f3k9m2p4q");

        assertThat(credentials.resolve(TENANT, PROJECT, USER, "https://git/x.git", null)
                .storeAccount()).isEqualTo("acc_7f3k9m2p4q");
    }

    @Test
    void unresolvableSource_doesNotFailTheInstall() {
        // Source resolution is someone else's logic; failing an install over
        // a credential lookup would be the wrong trade.
        when(sources.resolve(any(), any())).thenThrow(new KitException("no source"));

        KitAccess access = credentials.resolve(TENANT, PROJECT, USER, LIBRARY_URL, "vst_typed");

        assertThat(access.token()).isEqualTo("vst_typed");
        assertThat(access.storeAccount()).isNull();
    }

    @Test
    void blankUrl_skipsLookupEntirely() {
        KitAccess access = credentials.resolve(TENANT, PROJECT, USER, "  ", null);

        assertThat(access.storeAccount()).isNull();
        verify(sources, never()).resolve(any(), any());
    }

    @Test
    void anonymousCaller_stillCascadesToProjectAndTenant() {
        // A null user is not an error: a scheduler or a tool may install
        // without one, and the shared layers of the cascade still apply.
        givenSource(KitSourceType.LIBRARY);
        when(settings.getStringValueUserProjectCascade(
                eq(TENANT), isNull(), eq(PROJECT), isNull(), any()))
                .thenReturn("acc_shared00");

        assertThat(credentials.resolve(TENANT, PROJECT, null, LIBRARY_URL, null)
                .storeAccount()).isEqualTo("acc_shared00");
    }

    private void givenSource(KitSourceType type) {
        when(sources.resolve(any(), any())).thenReturn(KitSourceDto.builder()
                .id("vancetope-library")
                .type(type)
                .url(LIBRARY_URL)
                .build());
    }
}
