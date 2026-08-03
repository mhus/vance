package de.mhus.vance.foot.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.mhus.vance.api.chat.ChatMessageAppendedData;
import de.mhus.vance.api.chat.ChatRole;
import de.mhus.vance.foot.auth.VancePaths;
import de.mhus.vance.foot.config.FootConfig;
import de.mhus.vance.foot.session.SessionService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ConversationAuditServiceTest {

    @TempDir
    Path tempDir;

    private VancePaths vancePathsMock() {
        VancePaths mock = mock(VancePaths.class);
        when(mock.activeDir()).thenReturn(tempDir);
        return mock;
    }

    private SessionService sessionsWith(String sessionId, String projectId) {
        SessionService mock = mock(SessionService.class);
        when(mock.current()).thenReturn(new SessionService.BoundSession(
                sessionId, projectId, null, null));
        return mock;
    }

    private ChatMessageAppendedData msg(ChatRole role, String content, String processName) {
        return ChatMessageAppendedData.builder()
                .chatMessageId("msg-1")
                .thinkProcessId("proc-1")
                .processName(processName)
                .role(role)
                .content(content)
                .createdAt(Instant.parse("2026-08-03T10:00:00Z"))
                .build();
    }

    @Test
    void disabled_doesNothing() {
        FootConfig config = new FootConfig();
        config.getConversationAudit().setEnabled(false);
        SessionService sessions = sessionsWith("sess-123", "proj-1");
        ConversationAuditService svc = new ConversationAuditService(
                config, vancePathsMock(), sessions);

        svc.append(msg(ChatRole.USER, "hello", "arthur"));

        assertThat(Files.exists(tempDir.resolve("conversations"))).isFalse();
    }

    @Test
    void noSessionBound_doesNothing() {
        FootConfig config = new FootConfig();
        config.getConversationAudit().setEnabled(true);
        SessionService sessions = mock(SessionService.class);
        when(sessions.current()).thenReturn(null);
        ConversationAuditService svc = new ConversationAuditService(
                config, vancePathsMock(), sessions);

        svc.append(msg(ChatRole.USER, "hello", "arthur"));

        assertThat(Files.exists(tempDir.resolve("conversations"))).isFalse();
    }

    @Test
    void appendsUserAndAssistantMessagesToJsonl() throws Exception {
        FootConfig config = new FootConfig();
        config.getConversationAudit().setEnabled(true);
        SessionService sessions = sessionsWith("sess-123", "proj-1");
        Clock fixedClock = Clock.fixed(
                Instant.parse("2026-08-03T10:30:00Z"), ZoneId.systemDefault());
        ConversationAuditService svc = new ConversationAuditService(
                config, vancePathsMock(), sessions, fixedClock);

        svc.append(msg(ChatRole.USER, "what is 2+2?", "arthur"));
        svc.append(msg(ChatRole.ASSISTANT, "4", "arthur"));

        Path file = tempDir.resolve("conversations/2026-08/sess-123.jsonl");
        assertThat(Files.exists(file)).isTrue();
        List<String> lines = Files.readAllLines(file);
        assertThat(lines).hasSize(2);

        // First line: USER
        assertThat(lines.get(0)).contains("\"role\":\"user\"");
        assertThat(lines.get(0)).contains("\"content\":\"what is 2+2?\"");
        assertThat(lines.get(0)).contains("\"sessionId\":\"sess-123\"");
        assertThat(lines.get(0)).contains("\"processName\":\"arthur\"");

        // Second line: ASSISTANT
        assertThat(lines.get(1)).contains("\"role\":\"assistant\"");
        assertThat(lines.get(1)).contains("\"content\":\"4\"");
    }

    @Test
    void createsMonthDirectoryFromClock() throws Exception {
        FootConfig config = new FootConfig();
        config.getConversationAudit().setEnabled(true);
        SessionService sessions = sessionsWith("sess-456", "proj-1");
        Clock fixedClock = Clock.fixed(
                Instant.parse("2026-12-15T08:00:00Z"), ZoneId.systemDefault());
        ConversationAuditService svc = new ConversationAuditService(
                config, vancePathsMock(), sessions, fixedClock);

        svc.append(msg(ChatRole.USER, "winter", "arthur"));

        Path file = tempDir.resolve("conversations/2026-12/sess-456.jsonl");
        assertThat(Files.exists(file)).isTrue();
    }

    @Test
    void customDirOverridesDefault() throws Exception {
        FootConfig config = new FootConfig();
        config.getConversationAudit().setEnabled(true);
        config.getConversationAudit().setDir("chat-logs");
        SessionService sessions = sessionsWith("sess-789", "proj-1");
        ConversationAuditService svc = new ConversationAuditService(
                config, vancePathsMock(), sessions);

        svc.append(msg(ChatRole.USER, "hello", "arthur"));

        Path file = tempDir.resolve("chat-logs");
        // Month dir depends on the real clock — just check the base dir exists
        assertThat(Files.isDirectory(file)).isTrue();
    }

    @Test
    void includesSenderMetadataWhenPresent() throws Exception {
        FootConfig config = new FootConfig();
        config.getConversationAudit().setEnabled(true);
        SessionService sessions = sessionsWith("sess-meta", "proj-1");
        ConversationAuditService svc = new ConversationAuditService(
                config, vancePathsMock(), sessions);

        ChatMessageAppendedData data = ChatMessageAppendedData.builder()
                .chatMessageId("msg-meta")
                .thinkProcessId("proc-1")
                .processName("arthur")
                .role(ChatRole.USER)
                .content("from alice")
                .senderUserId("user-alice")
                .senderDisplayName("Alice")
                .createdAt(Instant.parse("2026-08-03T10:00:00Z"))
                .build();
        svc.append(data);

        Path file = tempDir.resolve("conversations");
        // Find the jsonl file in the month subdirectory
        Path monthDir = Files.list(file).findFirst().orElseThrow();
        Path jsonlFile = Files.list(monthDir).findFirst().orElseThrow();
        String content = Files.readString(jsonlFile);

        assertThat(content).contains("\"senderUserId\":\"user-alice\"");
        assertThat(content).contains("\"senderDisplayName\":\"Alice\"");
    }

    @Test
    void isEnabled_reflectsConfig() {
        FootConfig config = new FootConfig();
        config.getConversationAudit().setEnabled(true);
        ConversationAuditService svc = new ConversationAuditService(
                config, vancePathsMock(), mock(SessionService.class));
        assertThat(svc.isEnabled()).isTrue();

        config.getConversationAudit().setEnabled(false);
        assertThat(svc.isEnabled()).isFalse();
    }

    @Test
    void multipleAppendsAccumulateInSameFile() throws Exception {
        FootConfig config = new FootConfig();
        config.getConversationAudit().setEnabled(true);
        SessionService sessions = sessionsWith("sess-multi", "proj-1");
        Clock fixedClock = Clock.fixed(
                Instant.parse("2026-08-03T10:00:00Z"), ZoneId.systemDefault());
        ConversationAuditService svc = new ConversationAuditService(
                config, vancePathsMock(), sessions, fixedClock);

        for (int i = 0; i < 5; i++) {
            svc.append(msg(ChatRole.USER, "msg-" + i, "arthur"));
        }

        Path file = tempDir.resolve("conversations/2026-08/sess-multi.jsonl");
        List<String> lines = Files.readAllLines(file);
        assertThat(lines).hasSize(5);
        assertThat(lines.get(0)).contains("msg-0");
        assertThat(lines.get(4)).contains("msg-4");
    }
}
