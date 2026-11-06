package de.mhus.vance.brain.ursaeventtrigger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

/**
 * The event endpoint has no JWT, so how much of a body is read must not be up to the caller: a declared length
 * over the cap is answered without reading, and a body that is longer than it said stops at the cap.
 */
class UrsaEventControllerBodyLimitTest {

    private final UrsaEventService service = mock(UrsaEventService.class);
    private final UrsaEventController controller =
            new UrsaEventController(service, JsonMapper.builder().build(), new UrsaEventForwarder("t"), 16);

    UrsaEventControllerBodyLimitTest() {
        when(service.trigger(any(), any(), any(), any(), any(), any(), anyBoolean(), any()))
                .thenReturn(new UrsaEventService.UrsaEventTriggerResult("w", "run-1", null, "evt_1", Instant.EPOCH));
    }

    private static MockHttpServletRequest post(String body, boolean declareLength) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/brain/acme/event/p/e") {
            @Override
            public long getContentLengthLong() {
                return declareLength ? super.getContentLengthLong() : -1;
            }
        };
        request.setContentType(MediaType.APPLICATION_JSON_VALUE);
        request.setContent(body.getBytes(StandardCharsets.UTF_8));
        return request;
    }

    private void assertTooLarge(MockHttpServletRequest request) {
        assertThatThrownBy(() -> controller.triggerPost("acme", "p", "e", request))
                .isInstanceOfSatisfying(
                        ResponseStatusException.class,
                        ex -> assertThat(ex.getStatusCode().value()).isEqualTo(413));
    }

    @Test
    void aBodyWithinTheCapGoesThrough() {
        assertThat(controller
                        .triggerPost("acme", "p", "e", post("{\"a\":1}", true))
                        .getBody())
                .extracting(b -> ((de.mhus.vance.api.ursaevents.EventTriggerResponse) b).getWorkflowRunId())
                .isEqualTo("run-1");
    }

    @Test
    void aDeclaredLengthOverTheCapIsRefusedWithoutReadingTheBody() {
        MockHttpServletRequest request = post("{\"a\":\"" + "x".repeat(100) + "\"}", true);

        assertTooLarge(request);
        // Nothing was consumed: the stream is still whole.
        assertThat(request.getContentAsByteArray()).hasSizeGreaterThan(100);
    }

    @Test
    void aBodyThatSaysNothingAboutItsLengthAndRunsOnIsStoppedAtTheCap() {
        assertTooLarge(post("{\"a\":\"" + "x".repeat(100) + "\"}", false));
    }

    @Test
    void aBodyExactlyAtTheCapIsStillAccepted() {
        String exactly = "{\"a\":\"" + "x".repeat(8) + "\"}"; // 16 characters
        assertThat(exactly.length()).isEqualTo(16);

        assertThat(controller
                        .triggerPost("acme", "p", "e", post(exactly, false))
                        .getBody())
                .extracting(b -> ((de.mhus.vance.api.ursaevents.EventTriggerResponse) b).getWorkflowRunId())
                .isEqualTo("run-1");
    }

    @Test
    void aFormBodyBecomesAMap_decodedAndWithRepeatedNamesAsLists() {
        assertThat(UrsaEventController.parseForm("command=%2Fdeploy&text=hello+world&tag=a&tag=b&flag"))
                .containsEntry("command", "/deploy")
                .containsEntry("text", "hello world")
                .containsEntry("tag", java.util.List.of("a", "b"))
                .containsEntry("flag", "");
        assertThat(UrsaEventController.parseForm("")).isEmpty();
    }

    @Test
    void aMalformedPercentEscapeInAFormBodyIsABadRequest_notAServerError() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> UrsaEventController.parseForm("a=%zz"))
                .isInstanceOfSatisfying(
                        org.springframework.web.server.ResponseStatusException.class,
                        ex -> assertThat(ex.getStatusCode().value()).isEqualTo(400));
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> UrsaEventController.parseForm("a=%4"))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    }
}
