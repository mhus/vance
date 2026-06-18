package de.mhus.vance.brain.eddie.connection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import de.mhus.vance.api.ws.MessageType;
import de.mhus.vance.api.ws.WebSocketEnvelope;
import de.mhus.vance.shared.thinkprocess.WorkerLinkSnapshot;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Frame-classification tests. The handlers themselves come in steps 3c
 * (chat) and 4 (plan) — here we only verify the router routes the right
 * envelope types to the right slot and silently drops everything else.
 */
class EddieFrameRouterTest {

    private final WorkerLinkSnapshot link = WorkerLinkSnapshot.builder()
            .workerProcessId("w-1").workerProcessName("arthur").workerProjectName("p")
            .build();

    @Test
    void planFrames_routedToPlanHandler() {
        var captured = new ArrayList<String>();
        EddieFrameRouter router = new EddieFrameRouter(
                (env, l) -> captured.add(env.getType()),
                null, null);

        router.onFrame(notif(MessageType.TODOS_UPDATED), link);
        router.onFrame(notif(MessageType.PLAN_PROPOSED), link);
        router.onFrame(notif(MessageType.PROCESS_MODE_CHANGED), link);

        assertThat(captured).containsExactly(
                MessageType.TODOS_UPDATED,
                MessageType.PLAN_PROPOSED,
                MessageType.PROCESS_MODE_CHANGED);
    }

    @Test
    void chatFrames_routedToChatHandler() {
        var captured = new ArrayList<String>();
        EddieFrameRouter router = new EddieFrameRouter(
                null,
                (env, l) -> captured.add(env.getType()),
                null);

        router.onFrame(notif(MessageType.CHAT_MESSAGE_APPENDED), link);
        router.onFrame(notif(MessageType.CHAT_MESSAGE_STREAM_CHUNK), link);

        assertThat(captured).containsExactly(
                MessageType.CHAT_MESSAGE_APPENDED,
                MessageType.CHAT_MESSAGE_STREAM_CHUNK);
    }

    @Test
    void progressFrames_routedToProgressHandler() {
        var captured = new ArrayList<String>();
        EddieFrameRouter router = new EddieFrameRouter(
                null, null,
                (env, l) -> captured.add(env.getType()));

        router.onFrame(notif(MessageType.PROCESS_PROGRESS), link);

        assertThat(captured).containsExactly(MessageType.PROCESS_PROGRESS);
    }

    @Test
    void unknownFrameType_isSilentlyDropped() {
        var captured = new ArrayList<String>();
        EddieFrameRouter router = new EddieFrameRouter(
                (env, l) -> captured.add(env.getType()),
                (env, l) -> captured.add(env.getType()),
                (env, l) -> captured.add(env.getType()));

        router.onFrame(notif("welcome"), link);
        router.onFrame(notif("pong"), link);
        router.onFrame(notif("error"), link);
        router.onFrame(notif("session-resume"), link);

        assertThat(captured).isEmpty();
    }

    @Test
    void noOpRouter_dropsEverythingWithoutNPE() {
        EddieFrameRouter router = EddieFrameRouter.noOp();

        // None of these should hit a null-handler dereference.
        for (String t : List.of(
                MessageType.TODOS_UPDATED,
                MessageType.CHAT_MESSAGE_APPENDED,
                MessageType.PROCESS_PROGRESS,
                "unknown")) {
            assertThatCode(() -> router.onFrame(notif(t), link))
                    .doesNotThrowAnyException();
        }
    }

    @Test
    void nullType_isSafe() {
        var captured = new ArrayList<String>();
        EddieFrameRouter router = new EddieFrameRouter(
                (env, l) -> captured.add("plan"),
                (env, l) -> captured.add("chat"),
                (env, l) -> captured.add("progress"));
        WebSocketEnvelope env = new WebSocketEnvelope();
        env.setType(null);

        assertThatCode(() -> router.onFrame(env, link)).doesNotThrowAnyException();
        assertThat(captured).isEmpty();
    }

    private static WebSocketEnvelope notif(String type) {
        return WebSocketEnvelope.notification(type, null);
    }
}
