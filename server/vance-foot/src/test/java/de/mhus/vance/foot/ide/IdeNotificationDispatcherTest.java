package de.mhus.vance.foot.ide;

import static org.assertj.core.api.Assertions.assertThat;

import de.mhus.vance.foot.ide.dto.AtMentioned;
import de.mhus.vance.foot.ide.dto.SelectionChanged;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class IdeNotificationDispatcherTest {

    private final IdeNotificationDispatcher dispatcher = new IdeNotificationDispatcher();

    @Test
    void atMentioned_withRange_isParsedAndForwarded() throws Exception {
        List<AtMentioned> received = new ArrayList<>();
        dispatcher.register(new IdeBridgeListener() {
            @Override
            public void onAtMentioned(AtMentioned mention) {
                received.add(mention);
            }
        });

        JsonNode params = JsonMapper.builder().build().readTree(
                "{ \"filePath\": \"/abs/Foo.java\", \"lineStart\": 41, \"lineEnd\": 57 }");
        dispatcher.dispatch(new IdeMcpClient.Notification("at_mentioned", params));

        assertThat(received).hasSize(1);
        AtMentioned mention = received.get(0);
        assertThat(mention.filePath()).isEqualTo("/abs/Foo.java");
        assertThat(mention.lineStart()).isEqualTo(41);
        assertThat(mention.lineEnd()).isEqualTo(57);
    }

    @Test
    void atMentioned_withoutRange_yieldsNullLines() throws Exception {
        List<AtMentioned> received = new ArrayList<>();
        dispatcher.register(new IdeBridgeListener() {
            @Override
            public void onAtMentioned(AtMentioned mention) {
                received.add(mention);
            }
        });

        JsonNode params = JsonMapper.builder().build().readTree(
                "{ \"filePath\": \"/abs/Foo.java\" }");
        dispatcher.dispatch(new IdeMcpClient.Notification("at_mentioned", params));

        assertThat(received).hasSize(1);
        assertThat(received.get(0).lineStart()).isNull();
        assertThat(received.get(0).lineEnd()).isNull();
    }

    @Test
    void atMentioned_withoutFilePath_isDropped() throws Exception {
        List<AtMentioned> received = new ArrayList<>();
        dispatcher.register(new IdeBridgeListener() {
            @Override
            public void onAtMentioned(AtMentioned mention) {
                received.add(mention);
            }
        });

        JsonNode params = JsonMapper.builder().build().readTree("{ \"lineStart\": 1 }");
        dispatcher.dispatch(new IdeMcpClient.Notification("at_mentioned", params));

        assertThat(received).isEmpty();
    }

    @Test
    void selectionChanged_isParsedAndForwarded() throws Exception {
        List<SelectionChanged> received = new ArrayList<>();
        dispatcher.register(new IdeBridgeListener() {
            @Override
            public void onSelectionChanged(SelectionChanged sel) {
                received.add(sel);
            }
        });

        JsonNode params = JsonMapper.builder().build().readTree(
                """
                { "filePath": "/abs/Bar.java",
                  "selection": { "start": { "line": 10, "character": 0 },
                                 "end":   { "line": 12, "character": 5 } },
                  "text": "snippet" }""");
        dispatcher.dispatch(new IdeMcpClient.Notification("selection_changed", params));

        assertThat(received).hasSize(1);
        SelectionChanged sel = received.get(0);
        assertThat(sel.filePath()).isEqualTo("/abs/Bar.java");
        assertThat(sel.text()).isEqualTo("snippet");
        assertThat(sel.selection()).isNotNull();
        assertThat(sel.selection().start().line()).isEqualTo(10);
        assertThat(sel.selection().end().character()).isEqualTo(5);
    }

    @Test
    void selectionChanged_withoutSelection_propagatesNull() throws Exception {
        List<SelectionChanged> received = new ArrayList<>();
        dispatcher.register(new IdeBridgeListener() {
            @Override
            public void onSelectionChanged(SelectionChanged sel) {
                received.add(sel);
            }
        });

        JsonNode params = JsonMapper.builder().build().readTree(
                "{ \"filePath\": \"/abs/Bar.java\" }");
        dispatcher.dispatch(new IdeMcpClient.Notification("selection_changed", params));

        assertThat(received).hasSize(1);
        assertThat(received.get(0).selection()).isNull();
        assertThat(received.get(0).text()).isNull();
    }

    @Test
    void unknownNotification_isDropped() {
        List<AtMentioned> received = new ArrayList<>();
        dispatcher.register(new IdeBridgeListener() {
            @Override
            public void onAtMentioned(AtMentioned mention) {
                received.add(mention);
            }
        });

        dispatcher.dispatch(new IdeMcpClient.Notification("notifications/cancelled", null));

        assertThat(received).isEmpty();
    }

    @Test
    void connectionState_isFannedOutToAllListeners() {
        List<Boolean> a = new ArrayList<>();
        List<Boolean> b = new ArrayList<>();
        dispatcher.register(new IdeBridgeListener() {
            @Override
            public void onConnectionStateChanged(boolean connected) {
                a.add(connected);
            }
        });
        dispatcher.register(new IdeBridgeListener() {
            @Override
            public void onConnectionStateChanged(boolean connected) {
                b.add(connected);
            }
        });

        dispatcher.notifyConnectionState(true);
        dispatcher.notifyConnectionState(false);

        assertThat(a).containsExactly(true, false);
        assertThat(b).containsExactly(true, false);
    }

    @Test
    void listenerThrowing_doesNotBreakOtherListeners() throws Exception {
        List<AtMentioned> received = new ArrayList<>();
        dispatcher.register(new IdeBridgeListener() {
            @Override
            public void onAtMentioned(AtMentioned mention) {
                throw new RuntimeException("kaboom");
            }
        });
        dispatcher.register(new IdeBridgeListener() {
            @Override
            public void onAtMentioned(AtMentioned mention) {
                received.add(mention);
            }
        });

        JsonNode params = JsonMapper.builder().build().readTree(
                "{ \"filePath\": \"/x\", \"lineStart\": 0, \"lineEnd\": 0 }");
        dispatcher.dispatch(new IdeMcpClient.Notification("at_mentioned", params));

        assertThat(received).hasSize(1);
    }
}
