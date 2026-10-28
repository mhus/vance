package de.mhus.vance.runner.protocol;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.mhus.vance.age.AgeKeys;
import de.mhus.vance.runner.protocol.RunnerEnvelope.OpenedBytes;
import de.mhus.vance.runner.protocol.RunnerEnvelope.ReplyHandle;
import de.mhus.vance.runner.protocol.RunnerEnvelope.SealedRequest;
import de.mhus.vance.runner.protocol.RunnerProtocolException.Kind;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class RunnerEnvelopeTest {

    private static final String TOKEN = "header.payload.signature-that-must-not-be-visible";
    private static final String JTI = "jti-0001";

    private final AgeKeys.AgeKeyPair runner = AgeKeys.generateKeyPair();

    private SealedRequest request(String op, Object args, byte[] body) {
        return RunnerEnvelope.sealRequest(runner.recipient(), TOKEN, JTI, op, args, body);
    }

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void requestAndAnswer_roundTrip_withArgumentsAndBodies() {
        SealedRequest sealed = request("file.write", Map.of("path", "a/b.txt"), bytes("file content"));

        OpenedBytes<RunnerRequest> opened = RunnerEnvelope.openRequest(runner.identity(), sealed.bytes());

        assertThat(opened.header().v()).isEqualTo(RunnerEnvelope.VERSION);
        assertThat(opened.header().token()).isEqualTo(TOKEN);
        assertThat(opened.header().op()).isEqualTo("file.write");
        assertThat(opened.header().args().get("path").asString()).isEqualTo("a/b.txt");
        assertThat(opened.body()).isEqualTo(bytes("file content"));

        byte[] answer = RunnerEnvelope.sealResponse(
                opened.header().replyKey(),
                RunnerResponse.ok(JTI, JsonMapper.shared().valueToTree(Map.of("written", 12))),
                bytes("echo"));
        OpenedBytes<RunnerResponse> reply = sealed.reply().openResponse(answer);

        assertThat(reply.header().status()).isEqualTo(RunnerResponse.Status.OK);
        assertThat(reply.header().result().get("written").asInt()).isEqualTo(12);
        assertThat(reply.body()).isEqualTo(bytes("echo"));
    }

    @Test
    void errorAnswer_carriesCodeAndMessage() {
        SealedRequest sealed = request("exec.start", null, new byte[0]);
        OpenedBytes<RunnerRequest> opened = RunnerEnvelope.openRequest(runner.identity(), sealed.bytes());

        byte[] answer = RunnerEnvelope.sealResponse(
                opened.header().replyKey(),
                RunnerResponse.error(JTI, "no-such-root", "RootDir 'x' does not exist"),
                new byte[0]);
        RunnerResponse header = sealed.reply().openResponse(answer).header();

        assertThat(header.status()).isEqualTo(RunnerResponse.Status.ERROR);
        assertThat(header.error().code()).isEqualTo("no-such-root");
        assertThat(header.error().message()).contains("does not exist");
        assertThat(header.result()).isNull();
    }

    @Test
    void absentArgsAndEmptyBody_areFine() {
        SealedRequest sealed = request("ping", null, new byte[0]);

        OpenedBytes<RunnerRequest> opened = RunnerEnvelope.openRequest(runner.identity(), sealed.bytes());

        assertThat(opened.header().args()).isNull();
        assertThat(opened.body()).isEmpty();
    }

    @Test
    void theWire_revealsNothingOfTheRequest() {
        SealedRequest sealed = request("file.read", Map.of("path", "secret-plans.txt"), bytes("top secret body"));

        String wire = new String(sealed.bytes(), StandardCharsets.ISO_8859_1);

        assertThat(wire).startsWith("age-encryption.org/v1");
        assertThat(wire)
                .doesNotContain("signature-that-must-not-be-visible")
                .doesNotContain("file.read")
                .doesNotContain("secret-plans")
                .doesNotContain("top secret");
    }

    @Test
    void aWrongRunner_cannotOpenTheRequest() {
        SealedRequest sealed = request("ping", null, new byte[0]);
        String strangersIdentity = AgeKeys.generateKeyPair().identity();

        assertThatThrownBy(() -> RunnerEnvelope.openRequest(strangersIdentity, sealed.bytes()))
                .isInstanceOfSatisfying(
                        RunnerProtocolException.class, e -> assertThat(e.kind()).isEqualTo(Kind.NOT_FOR_US));
    }

    @Test
    void garbage_isNotForUs() {
        assertThatThrownBy(() -> RunnerEnvelope.openRequest(runner.identity(), bytes("GET / HTTP/1.1")))
                .isInstanceOfSatisfying(
                        RunnerProtocolException.class, e -> assertThat(e.kind()).isEqualTo(Kind.NOT_FOR_US));
        assertThatThrownBy(() -> RunnerEnvelope.openRequest(runner.identity(), new byte[0]))
                .isInstanceOf(RunnerProtocolException.class);
    }

    @Test
    void everyRequestGetsItsOwnReplyKey_andAnswersDoNotCross() {
        SealedRequest first = request("ping", null, new byte[0]);
        SealedRequest second = request("ping", null, new byte[0]);
        RunnerRequest h1 =
                RunnerEnvelope.openRequest(runner.identity(), first.bytes()).header();
        RunnerRequest h2 =
                RunnerEnvelope.openRequest(runner.identity(), second.bytes()).header();
        assertThat(h1.replyKey()).isNotEqualTo(h2.replyKey());

        byte[] answerToFirst = RunnerEnvelope.sealResponse(h1.replyKey(), RunnerResponse.ok(JTI, null), new byte[0]);

        assertThat(first.reply().openResponse(answerToFirst).header().status()).isEqualTo(RunnerResponse.Status.OK);
        assertThatThrownBy(() -> second.reply().openResponse(answerToFirst))
                .isInstanceOfSatisfying(
                        RunnerProtocolException.class, e -> assertThat(e.kind()).isEqualTo(Kind.WRONG_REPLY));
    }

    @Test
    void anAnswerForAnotherJti_isRefused_evenWithTheRightKey() {
        SealedRequest sealed = request("ping", null, new byte[0]);
        RunnerRequest header =
                RunnerEnvelope.openRequest(runner.identity(), sealed.bytes()).header();

        byte[] answer =
                RunnerEnvelope.sealResponse(header.replyKey(), RunnerResponse.ok("another-jti", null), new byte[0]);

        assertThatThrownBy(() -> sealed.reply().openResponse(answer))
                .isInstanceOfSatisfying(
                        RunnerProtocolException.class, e -> assertThat(e.kind()).isEqualTo(Kind.WRONG_REPLY));
    }

    @Test
    void anAnswerEncryptedToSomeoneElse_isNotAcceptedAsTheRunners() {
        SealedRequest sealed = request("ping", null, new byte[0]);
        // An impostor knows the jti (it is not secret) but cannot know the one-time key.
        byte[] forged = RunnerEnvelope.sealResponse(
                AgeKeys.generateKeyPair().recipient(), RunnerResponse.ok(JTI, null), new byte[0]);

        assertThatThrownBy(() -> sealed.reply().openResponse(forged))
                .isInstanceOfSatisfying(
                        RunnerProtocolException.class, e -> assertThat(e.kind()).isEqualTo(Kind.WRONG_REPLY));
    }

    @Test
    void aTamperedOrTruncatedAnswer_failsWhileReadingTheBody() {
        SealedRequest sealed = request("file.read", null, new byte[0]);
        RunnerRequest header =
                RunnerEnvelope.openRequest(runner.identity(), sealed.bytes()).header();
        byte[] body = new byte[200_000];
        new Random(7).nextBytes(body);
        byte[] answer = RunnerEnvelope.sealResponse(header.replyKey(), RunnerResponse.ok(JTI, null), body);

        byte[] cut = Arrays.copyOf(answer, answer.length - 40);
        byte[] flipped = answer.clone();
        flipped[flipped.length - 20] ^= 0x01;

        for (byte[] broken : new byte[][] {cut, flipped}) {
            assertThatThrownBy(() -> sealed.reply().openResponse(broken))
                    .isInstanceOfSatisfying(
                            RunnerProtocolException.class,
                            e -> assertThat(e.kind()).isEqualTo(Kind.MALFORMED));
        }
    }

    @Test
    void bodiesStream_withoutBeingHeldWhole() throws IOException {
        byte[] big = new byte[3 * 1024 * 1024];
        new Random(3).nextBytes(big);
        ByteArrayOutputStream wire = new ByteArrayOutputStream();

        ReplyHandle reply = RunnerEnvelope.sealRequest(
                runner.recipient(),
                TOKEN,
                JTI,
                "file.write",
                Map.of("path", "big.bin"),
                new ByteArrayInputStream(big),
                wire);
        RunnerEnvelope.OpenedRequest opened =
                RunnerEnvelope.openRequest(runner.identity(), new ByteArrayInputStream(wire.toByteArray()));
        try (var body = opened.body()) {
            assertThat(body.readAllBytes()).isEqualTo(big);
        }
        ByteArrayOutputStream answerWire = new ByteArrayOutputStream();
        RunnerEnvelope.sealResponse(
                opened.header().replyKey(), RunnerResponse.ok(JTI, null), new ByteArrayInputStream(big), answerWire);
        RunnerEnvelope.OpenedResponse answer = reply.openResponse(new ByteArrayInputStream(answerWire.toByteArray()));
        try (var body = answer.body()) {
            assertThat(body.readAllBytes()).isEqualTo(big);
        }
    }

    @Test
    void anUnknownVersion_isRefused() throws IOException {
        byte[] sealed = sealRaw(
                runner.recipient(),
                new RunnerRequest(99, TOKEN, AgeKeys.generateKeyPair().recipient(), "ping", null));

        assertThatThrownBy(() -> RunnerEnvelope.openRequest(runner.identity(), sealed))
                .isInstanceOfSatisfying(
                        RunnerProtocolException.class, e -> assertThat(e.kind()).isEqualTo(Kind.UNSUPPORTED_VERSION));
    }

    @Test
    void aRequestWithoutAValidReplyKeyTokenOrOp_isMalformed() throws IOException {
        String ok = AgeKeys.generateKeyPair().recipient();
        for (RunnerRequest bad : new RunnerRequest[] {
            new RunnerRequest(1, TOKEN, "not-an-age-key", "ping", null),
            new RunnerRequest(1, TOKEN, ok, "", null),
            new RunnerRequest(1, "", ok, "ping", null)
        }) {
            byte[] sealed = sealRaw(runner.recipient(), bad);

            assertThatThrownBy(() -> RunnerEnvelope.openRequest(runner.identity(), sealed))
                    .isInstanceOfSatisfying(
                            RunnerProtocolException.class,
                            e -> assertThat(e.kind()).isEqualTo(Kind.MALFORMED));
        }
    }

    @Test
    void anAbsurdHeaderLength_isRefusedBeforeAnythingIsAllocated() throws IOException {
        ByteArrayOutputStream wire = new ByteArrayOutputStream();
        try (var out = de.mhus.vance.age.AgeStreams.encrypt(wire, java.util.List.of(runner.recipient()))) {
            out.write(ByteBuffer.allocate(4).putInt(Integer.MAX_VALUE).array());
            out.write(new byte[16]);
        }

        assertThatThrownBy(() -> RunnerEnvelope.openRequest(runner.identity(), wire.toByteArray()))
                .isInstanceOfSatisfying(RunnerProtocolException.class, e -> {
                    assertThat(e.kind()).isEqualTo(Kind.MALFORMED);
                    assertThat(e.getMessage()).contains("out of range");
                });
    }

    @Test
    void sealing_refusesMissingFields_andABadRecipient() {
        assertThatThrownBy(() -> RunnerEnvelope.sealRequest(runner.recipient(), "", JTI, "ping", null, new byte[0]))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RunnerEnvelope.sealRequest("not-a-key", TOKEN, JTI, "ping", null, new byte[0]))
                .isInstanceOfSatisfying(
                        RunnerProtocolException.class, e -> assertThat(e.kind()).isEqualTo(Kind.MALFORMED));
    }

    @Test
    void theReplyHandle_neverPrintsItsIdentity() {
        SealedRequest sealed = request("ping", null, new byte[0]);

        assertThat(sealed.reply().toString()).contains(JTI).doesNotContain("AGE-SECRET-KEY");
    }

    /** Seals an arbitrary header the way a (broken or hostile) brain might. */
    private static byte[] sealRaw(String recipient, RunnerRequest header) throws IOException {
        ByteArrayOutputStream wire = new ByteArrayOutputStream();
        try (var out = de.mhus.vance.age.AgeStreams.encrypt(wire, java.util.List.of(recipient))) {
            byte[] json = JsonMapper.shared().writeValueAsBytes(header);
            out.write(ByteBuffer.allocate(4).putInt(json.length).array());
            out.write(json);
        }
        return wire.toByteArray();
    }
}
