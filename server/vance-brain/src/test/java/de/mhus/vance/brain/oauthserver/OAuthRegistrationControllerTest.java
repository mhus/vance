package de.mhus.vance.brain.oauthserver;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.shared.oauthserver.OAuthServerClientDocument;
import de.mhus.vance.shared.oauthserver.OAuthServerClientService;
import de.mhus.vance.shared.tenant.TenantDocument;
import de.mhus.vance.shared.tenant.TenantService;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

class OAuthRegistrationControllerTest {

    private OAuthServerClientService clientService;
    private OAuthRegistrationController controller;

    @BeforeEach
    void setUp() {
        clientService = mock(OAuthServerClientService.class);
        TenantService tenantService = mock(TenantService.class);
        when(tenantService.findByName("acme"))
                .thenReturn(Optional.of(TenantDocument.builder().name("acme").build()));
        when(clientService.register(anyString(), anyString(), any(), any(), anyList()))
                .thenAnswer(inv -> OAuthServerClientDocument.builder()
                        .clientId("dcr_x")
                        .tenantId(inv.getArgument(0))
                        .clientName(inv.getArgument(1))
                        .redirectUris(inv.getArgument(4))
                        .createdAt(Instant.now())
                        .build());
        controller = new OAuthRegistrationController(
                new OAuthServerSettings("https://vance.example.com", true, 3600, 30, 365, true, true),
                clientService,
                tenantService);
    }

    private static MockHttpServletRequest from(String address) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr(address);
        return request;
    }

    private static Map<String, Object> body(String name, String redirect) {
        return Map.of("client_name", name, "redirect_uris", List.of(redirect));
    }

    @Test
    void register_validClient_isCreated() {
        ResponseEntity<Map<String, Object>> response =
                controller.register("acme", body("Claude Code", "http://127.0.0.1/callback"), from("93.184.215.14"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).containsEntry("client_id", "dcr_x");
    }

    @Test
    void register_perAddressBudget_isEnforcedBelowTheTenantCeiling() {
        for (int i = 0; i < OAuthRegistrationController.REGISTRATIONS_PER_ADDRESS_PER_HOUR; i++) {
            assertThat(controller
                            .register("acme", body("C" + i, "https://app.example/cb"), from("93.184.215.14"))
                            .getStatusCode())
                    .isEqualTo(HttpStatus.CREATED);
        }

        assertThat(controller
                        .register("acme", body("one more", "https://app.example/cb"), from("93.184.215.14"))
                        .getStatusCode())
                .isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(controller
                        .register("acme", body("other address", "https://app.example/cb"), from("93.184.215.15"))
                        .getStatusCode())
                .isEqualTo(HttpStatus.CREATED);
    }

    @Test
    void register_tenantCeiling_holdsAcrossAddresses() {
        int created = 0;
        for (int i = 0; i < OAuthRegistrationController.REGISTRATIONS_PER_HOUR + 5; i++) {
            ResponseEntity<Map<String, Object>> response =
                    controller.register("acme", body("C", "https://app.example/cb"), from("93.184.216." + i));
            if (response.getStatusCode() == HttpStatus.CREATED) {
                created++;
            }
        }

        assertThat(created).isEqualTo(OAuthRegistrationController.REGISTRATIONS_PER_HOUR);
    }

    @Test
    void register_redirectOnOwnOrigin_isRejected() {
        ResponseEntity<Map<String, Object>> response = controller.register(
                "acme", body("Sneaky", "https://vance.example.com/oauth-consent.html"), from("93.184.215.14"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).containsEntry("error", "invalid_redirect_uri");
        verify(clientService, never()).register(anyString(), anyString(), any(), any(), anyList());
    }

    @Test
    void register_clientName_isCleaned() {
        controller.register("acme", body("Vance\u202Etope\u200B", "https://app.example/cb"), from("93.184.215.14"));

        verify(clientService).register(eq("acme"), eq("Vancetope"), isNull(), isNull(), anyList());
    }

    @Test
    void register_invisibleOnlyName_fallsBackToUnnamed() {
        controller.register("acme", body("\u200B\u202E", "https://app.example/cb"), from("93.184.215.14"));

        verify(clientService).register(eq("acme"), eq("Unnamed client"), isNull(), isNull(), anyList());
    }
}
