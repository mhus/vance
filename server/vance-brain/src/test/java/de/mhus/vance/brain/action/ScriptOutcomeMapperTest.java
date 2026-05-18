package de.mhus.vance.brain.action;

import static org.assertj.core.api.Assertions.assertThat;

import de.mhus.vance.brain.script.ScriptExecutionException;
import de.mhus.vance.brain.script.ScriptExecutionException.ErrorClass;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Exhaustive table-walk over the §5.3 outcome mapping in
 * {@code planning/trigger-actions.md}.
 */
class ScriptOutcomeMapperTest {

    // ──────────────────── Wrapper-pattern (Object with `success`) ────────────────────

    @Test
    void map_success_true_yields_SUCCESS_with_payload_minus_success() {
        Map<String, Object> ret = new LinkedHashMap<>();
        ret.put("success", true);
        ret.put("foo", 1);
        ret.put("bar", "x");

        ActionResult r = ScriptOutcomeMapper.mapValue(ret);

        assertThat(r.outcome()).isEqualTo(ActionOutcome.SUCCESS);
        assertThat(r.output()).containsEntry("foo", 1).containsEntry("bar", "x")
                .doesNotContainKey("success");
    }

    @Test
    void map_success_false_yields_BUSINESS_ERROR_with_payload() {
        Map<String, Object> ret = new LinkedHashMap<>();
        ret.put("success", false);
        ret.put("error", "bad input");

        ActionResult r = ScriptOutcomeMapper.mapValue(ret);

        assertThat(r.outcome()).isEqualTo(ActionOutcome.BUSINESS_ERROR);
        assertThat(r.errorMessage()).isEqualTo("bad input");
        assertThat(r.output()).containsEntry("error", "bad input");
    }

    @Test
    void map_success_false_without_error_field_uses_generic_message() {
        ActionResult r = ScriptOutcomeMapper.mapValue(Map.of("success", false));

        assertThat(r.outcome()).isEqualTo(ActionOutcome.BUSINESS_ERROR);
        assertThat(r.errorMessage()).contains("success:false");
    }

    @Test
    void map_success_as_non_boolean_yields_TECHNICAL_ERROR() {
        ActionResult r = ScriptOutcomeMapper.mapValue(Map.of("success", "yes"));

        assertThat(r.outcome()).isEqualTo(ActionOutcome.TECHNICAL_ERROR);
        assertThat(r.output()).containsEntry("error", "invalid-success-type");
    }

    // ──────────────────── No wrapper (plain Object) ────────────────────

    @Test
    void map_plain_object_without_success_yields_SUCCESS_with_full_object() {
        Map<String, Object> ret = Map.of("foo", 1, "bar", "x");

        ActionResult r = ScriptOutcomeMapper.mapValue(ret);

        assertThat(r.outcome()).isEqualTo(ActionOutcome.SUCCESS);
        assertThat(r.output()).isEqualTo(ret);
    }

    @Test
    void map_empty_object_yields_SUCCESS_with_empty_output() {
        ActionResult r = ScriptOutcomeMapper.mapValue(Map.of());

        assertThat(r.outcome()).isEqualTo(ActionOutcome.SUCCESS);
        assertThat(r.output()).isEmpty();
    }

    @Test
    void map_object_with_non_string_key_yields_TECHNICAL_ERROR() {
        Map<Object, Object> ret = new LinkedHashMap<>();
        ret.put(42, "value");

        ActionResult r = ScriptOutcomeMapper.mapValue(ret);

        assertThat(r.outcome()).isEqualTo(ActionOutcome.TECHNICAL_ERROR);
        assertThat(r.output()).containsEntry("error", "non-string-key");
    }

    // ──────────────────── Primitives + Arrays ────────────────────

    @Test
    void map_string_yields_SUCCESS_with_value_field() {
        ActionResult r = ScriptOutcomeMapper.mapValue("hello");

        assertThat(r.outcome()).isEqualTo(ActionOutcome.SUCCESS);
        assertThat(r.output()).containsEntry("value", "hello");
    }

    @Test
    void map_number_yields_SUCCESS_with_value_field() {
        ActionResult r = ScriptOutcomeMapper.mapValue(42);

        assertThat(r.outcome()).isEqualTo(ActionOutcome.SUCCESS);
        assertThat(r.output()).containsEntry("value", 42);
    }

    @Test
    void map_boolean_yields_SUCCESS_with_value_field() {
        ActionResult r = ScriptOutcomeMapper.mapValue(true);

        assertThat(r.outcome()).isEqualTo(ActionOutcome.SUCCESS);
        assertThat(r.output()).containsEntry("value", true);
    }

    @Test
    void map_list_yields_SUCCESS_with_value_field() {
        ActionResult r = ScriptOutcomeMapper.mapValue(List.of("a", "b"));

        assertThat(r.outcome()).isEqualTo(ActionOutcome.SUCCESS);
        assertThat(r.output()).containsEntry("value", List.of("a", "b"));
    }

    // ──────────────────── Null / void ────────────────────

    @Test
    void map_null_yields_SUCCESS_with_empty_output() {
        ActionResult r = ScriptOutcomeMapper.mapValue(null);

        assertThat(r.outcome()).isEqualTo(ActionOutcome.SUCCESS);
        assertThat(r.output()).isEmpty();
    }

    // ──────────────────── Non-serializable ────────────────────

    @Test
    void map_non_serializable_yields_TECHNICAL_ERROR() {
        Object unsupported = new Object();

        ActionResult r = ScriptOutcomeMapper.mapValue(unsupported);

        assertThat(r.outcome()).isEqualTo(ActionOutcome.TECHNICAL_ERROR);
        assertThat(r.errorMessage()).contains("non-serializable");
    }

    // ──────────────────── Exceptions ────────────────────

    @Test
    void map_guest_exception_yields_BUSINESS_ERROR() {
        ActionResult r = ScriptOutcomeMapper.mapException(
                new ScriptExecutionException(ErrorClass.GUEST_EXCEPTION, "TypeError: x is undefined"));

        assertThat(r.outcome()).isEqualTo(ActionOutcome.BUSINESS_ERROR);
        assertThat(r.errorMessage()).contains("TypeError");
        assertThat(r.output()).containsEntry("error", "TypeError: x is undefined");
    }

    @Test
    void map_timeout_yields_TIMEOUT_outcome() {
        ActionResult r = ScriptOutcomeMapper.mapException(
                new ScriptExecutionException(ErrorClass.TIMEOUT, "wall clock exceeded"));

        assertThat(r.outcome()).isEqualTo(ActionOutcome.TIMEOUT);
        assertThat(r.output()).containsEntry("error", "timeout");
    }

    @Test
    void map_resource_exhausted_yields_TECHNICAL_ERROR() {
        ActionResult r = ScriptOutcomeMapper.mapException(
                new ScriptExecutionException(ErrorClass.RESOURCE_EXHAUSTED, "200000 statements"));

        assertThat(r.outcome()).isEqualTo(ActionOutcome.TECHNICAL_ERROR);
        assertThat(r.output()).containsEntry("error", "resource_exhausted");
    }

    @Test
    void map_cancelled_yields_CANCELLED_outcome() {
        ActionResult r = ScriptOutcomeMapper.mapException(
                new ScriptExecutionException(ErrorClass.CANCELLED, "interrupted"));

        assertThat(r.outcome()).isEqualTo(ActionOutcome.CANCELLED);
    }

    @Test
    void map_host_exception_yields_TECHNICAL_ERROR() {
        ActionResult r = ScriptOutcomeMapper.mapException(
                new ScriptExecutionException(ErrorClass.HOST_EXCEPTION, "tool blew up"));

        assertThat(r.outcome()).isEqualTo(ActionOutcome.TECHNICAL_ERROR);
        assertThat(r.errorMessage()).contains("HOST_EXCEPTION");
    }

    @Test
    void map_invalid_header_yields_TECHNICAL_ERROR() {
        ActionResult r = ScriptOutcomeMapper.mapException(
                new ScriptExecutionException(ErrorClass.INVALID_HEADER, "@timeout abc"));

        assertThat(r.outcome()).isEqualTo(ActionOutcome.TECHNICAL_ERROR);
    }

    @Test
    void map_missing_capability_yields_TECHNICAL_ERROR() {
        ActionResult r = ScriptOutcomeMapper.mapException(
                new ScriptExecutionException(ErrorClass.MISSING_CAPABILITY, "needs doc_write"));

        assertThat(r.outcome()).isEqualTo(ActionOutcome.TECHNICAL_ERROR);
    }

    // ──────────────────── script-not-found sentinel ────────────────────

    @Test
    void scriptNotFound_helper_yields_TECHNICAL_ERROR_with_path() {
        ActionResult r = ScriptOutcomeMapper.scriptNotFound("scripts/gone.js");

        assertThat(r.outcome()).isEqualTo(ActionOutcome.TECHNICAL_ERROR);
        assertThat(r.errorMessage()).contains("scripts/gone.js");
        assertThat(r.output()).containsEntry("error", "script-not-found:scripts/gone.js");
    }
}
