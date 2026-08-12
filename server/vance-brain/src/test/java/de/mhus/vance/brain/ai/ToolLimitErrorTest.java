package de.mhus.vance.brain.ai;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Recognition of the "too many tool schemas" rejection. Verbatim bodies
 * from the field — the whole point is that the fingerprint survives a
 * provider reformatting its message.
 */
class ToolLimitErrorTest {

    private static final String OPENAI_BODY = """
            {
              "error": {
                "message": "Invalid 'tools': array too long. Expected an array with maximum length 128, but got an array with length 163 instead.",
                "type": "invalid_request_error",
                "param": "tools",
                "code": "array_above_max_length"
              }
            }""";

    @Test
    void openAiRejection_isRecognisedAndTheLimitParsed() {
        assertThat(ToolLimitError.isTooManyTools(OPENAI_BODY)).isTrue();
        assertThat(ToolLimitError.parseLimit(OPENAI_BODY)).hasValue(128);
    }

    @Test
    void errorCodeAlone_isEnoughToRecognise() {
        assertThat(ToolLimitError.isTooManyTools("code=array_above_max_length")).isTrue();
    }

    @Test
    void alternativeWording_isRecognised() {
        assertThat(ToolLimitError.isTooManyTools("Too many tools provided")).isTrue();
        assertThat(ToolLimitError.parseLimit("supports a maximum of 64 functions")).hasValue(64);
    }

    @Test
    void unrelatedError_isNotMistakenForIt() {
        assertThat(ToolLimitError.isTooManyTools(
                "rate_limit_exceeded: too many requests")).isFalse();
        assertThat(ToolLimitError.isTooManyTools("context_length_exceeded")).isFalse();
    }

    @Test
    void limitWithoutANumber_yieldsEmpty_soNothingIsGuessed() {
        assertThat(ToolLimitError.parseLimit("array too long")).isEmpty();
        assertThat(ToolLimitError.parseLimit(null)).isEmpty();
    }

    @Test
    void messageOf_walksTheCauseChain() {
        Throwable root = new IllegalStateException(OPENAI_BODY);
        Throwable wrapped = new RuntimeException("outer", new RuntimeException("middle", root));

        String text = ToolLimitError.messageOf(wrapped);

        assertThat(ToolLimitError.isTooManyTools(text)).isTrue();
        assertThat(text).contains("outer").contains("middle");
    }

    @Test
    void messageOf_toleratesNullAndSelfReferencingCause() {
        assertThat(ToolLimitError.messageOf(null)).isEmpty();
        assertThat(ToolLimitError.messageOf(new RuntimeException((String) null))).isEmpty();
    }
}
