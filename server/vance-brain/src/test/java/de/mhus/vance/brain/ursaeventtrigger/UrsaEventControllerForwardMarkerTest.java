package de.mhus.vance.brain.ursaeventtrigger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.ObjectMapper;

/**
 * Whether a request counts as already routed decides whether the receiving
 * pod resolves the project owner at all. The header alone must not settle
 * that question — the event route is JWT-free and never sees
 * {@code InternalAccessFilter}.
 */
class UrsaEventControllerForwardMarkerTest {

    private static final String TENANT = "acme";
    private static final String PROJECT = "p1";
    private static final String EVENT = "deploy";
    private static final String INTERNAL_TOKEN = "s3cret-internal";

    private UrsaEventService service;
    private UrsaEventController controller;
    private HttpServletRequest request;

    @BeforeEach
    void setUp() {
        service = mock(UrsaEventService.class);
        when(service.trigger(any(), any(), any(), any(), any(), any(), anyBoolean()))
                .thenReturn(new UrsaEventService.UrsaEventTriggerResult(
                        "w-deploy", "run-1", null, "evt_1", Instant.EPOCH));
        // GET carries no body, so the mapper is never asked anything here.
        controller = new UrsaEventController(
                service, mock(ObjectMapper.class), new UrsaEventForwarder(INTERNAL_TOKEN));
        request = mock(HttpServletRequest.class);
        when(request.getMethod()).thenReturn("GET");
        when(request.getRequestURI())
                .thenReturn("/brain/" + TENANT + "/event/" + PROJECT + "/" + EVENT);
    }

    @Test
    void spoofedForwardMarker_isIgnored_soTheOwnerIsStillResolved() {
        // Without the internal token anybody could set this header and make
        // the answering pod skip bringUpAndFindOwner — running the trigger on
        // a pod that does not hold the project's lane.
        when(request.getHeader(UrsaEventForwarder.FORWARDED_HEADER)).thenReturn("1");

        controller.triggerGet(TENANT, PROJECT, EVENT, request);

        assertThat(capturedForwardedFlag()).isFalse();
    }

    @Test
    void forwardMarkerWithWrongInternalToken_isIgnored() {
        when(request.getHeader(UrsaEventForwarder.FORWARDED_HEADER)).thenReturn("1");
        when(request.getHeader(UrsaEventForwarder.INTERNAL_TOKEN_HEADER)).thenReturn("guessed");

        controller.triggerGet(TENANT, PROJECT, EVENT, request);

        assertThat(capturedForwardedFlag()).isFalse();
    }

    @Test
    void genuineHop_isHonoured() {
        when(request.getHeader(UrsaEventForwarder.FORWARDED_HEADER)).thenReturn("1");
        when(request.getHeader(UrsaEventForwarder.INTERNAL_TOKEN_HEADER)).thenReturn(INTERNAL_TOKEN);

        controller.triggerGet(TENANT, PROJECT, EVENT, request);

        assertThat(capturedForwardedFlag()).isTrue();
    }

    @Test
    void ordinaryRequest_isNotForwarded() {
        controller.triggerGet(TENANT, PROJECT, EVENT, request);

        assertThat(capturedForwardedFlag()).isFalse();
    }

    private boolean capturedForwardedFlag() {
        ArgumentCaptor<Boolean> flag = ArgumentCaptor.forClass(Boolean.class);
        verify(service).trigger(eq(TENANT), eq(PROJECT), eq(EVENT), eq("GET"),
                any(), any(), flag.capture());
        return flag.getValue();
    }
}
