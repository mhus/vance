package de.mhus.vance.brain.ai;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;

/**
 * Pattern-based defaults for the per-model quirk fields
 * ({@code ModelInfo.messageParser()},
 * {@code ModelInfo.outputTokenParam()},
 * {@code ModelInfo.unsupportedParams()},
 * {@code ModelInfo.reasoningEffortWhenOff()}) — applied during
 * {@link ModelCatalog#lookup} when no per-model YAML has set the field
 * explicitly. Single bundled file
 * ({@code vance-defaults/model-quirks.yaml}) covers every provider
 * because the quirks are usually wire-protocol idiosyncrasies of a
 * model family (DeepSeek-V4 trailing-garbage, Gemma-4 tokenizer leaks,
 * gpt-5 rejecting {@code max_tokens}) that follow the model wherever
 * it's hosted.
 *
 * <h2>File format</h2>
 *
 * <pre>{@code
 * rules:
 *   - match: "deepseek-v4*"
 *     messageParser: "deepseek-v4"
 *   - match: "gpt-5*"
 *     outputTokenParam: "max_completion_tokens"
 *     unsupportedParams: ["temperature", "top_p"]
 * }</pre>
 *
 * <ul>
 *   <li>{@code match} is a simple glob over the model wire name
 *       (case-insensitive). Supported: {@code *} (zero-or-more
 *       characters) and {@code ?} (exactly one character).</li>
 *   <li>A rule carries at least one quirk field; any combination is
 *       allowed.</li>
 *   <li>Rule order = priority, <b>resolved per field</b>: the first
 *       matching rule that carries the requested field wins, so a
 *       parser-only rule doesn't shadow a later rule that sets the
 *       output-token field for the same model.</li>
 * </ul>
 *
 * <p>Layer position: this is the final fallback in each field's
 * cascade (project YAML → tenant YAML → {@code _vance} YAML → bundled
 * per-model YAML → bundled quirks → built-in default). Specific files
 * always beat patterns.
 */
@Component
@Slf4j
public class ModelQuirks {

    private static final String CLASSPATH = "vance-defaults/model-quirks.yaml";

    private final List<Rule> rules;

    public ModelQuirks() {
        this(new ClassPathResource(CLASSPATH));
    }

    /** Test-friendly constructor. */
    ModelQuirks(Resource resource) {
        this.rules = loadRules(resource);
        log.info("ModelQuirks: loaded {} rule(s) from {}", rules.size(), resource);
    }

    /**
     * Resolve the default {@code messageParser} for {@code modelName},
     * or empty when no rule matches.
     */
    public Optional<String> messageParserFor(@Nullable String modelName) {
        return firstMatch(modelName, Rule::messageParser);
    }

    /**
     * Resolve the default {@link OutputTokenParam} for
     * {@code modelName}, or empty when no rule sets one.
     */
    public Optional<OutputTokenParam> outputTokenParamFor(@Nullable String modelName) {
        return firstMatch(modelName, Rule::outputTokenParam);
    }

    /**
     * Resolve the sampling knobs {@code modelName} refuses, or empty
     * when no rule declares any.
     */
    public Optional<Set<SamplingParam>> unsupportedParamsFor(@Nullable String modelName) {
        return firstMatch(modelName, Rule::unsupportedParams);
    }

    /**
     * Resolve the explicit "no reasoning" wire value for
     * {@code modelName}, or empty when no rule sets one (the normal
     * case: omitting {@code reasoning_effort} means no reasoning).
     */
    public Optional<String> reasoningEffortWhenOffFor(@Nullable String modelName) {
        return firstMatch(modelName, Rule::reasoningEffortWhenOff);
    }

    private <T> Optional<T> firstMatch(
            @Nullable String modelName, java.util.function.Function<Rule, @Nullable T> field) {
        if (modelName == null || modelName.isBlank()) {
            return Optional.empty();
        }
        String lower = modelName.toLowerCase(Locale.ROOT);
        for (Rule rule : rules) {
            T value = field.apply(rule);
            if (value != null && rule.compiled.matcher(lower).matches()) {
                return Optional.of(value);
            }
        }
        return Optional.empty();
    }

    /** Test diagnostic. */
    int ruleCount() {
        return rules.size();
    }

    private static List<Rule> loadRules(Resource resource) {
        if (!resource.exists()) {
            log.warn("ModelQuirks: {} not found — no quirks applied", resource);
            return List.of();
        }
        Map<String, Object> root;
        try (InputStream in = resource.getInputStream()) {
            Object parsed = new Yaml().load(
                    new String(in.readAllBytes(), StandardCharsets.UTF_8));
            if (parsed == null) return List.of();
            if (!(parsed instanceof Map<?, ?> m)) {
                log.warn("ModelQuirks: {} root is not a map — ignored", resource);
                return List.of();
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> typed = (Map<String, Object>) m;
            root = typed;
        } catch (IOException | RuntimeException e) {
            log.warn("ModelQuirks: failed to read {}: {}", resource, e.toString());
            return List.of();
        }
        Object rulesNode = root.get("rules");
        if (!(rulesNode instanceof List<?> rawRules)) {
            return List.of();
        }
        List<Rule> out = new ArrayList<>(rawRules.size());
        int index = 0;
        for (Object rawRule : rawRules) {
            index++;
            if (!(rawRule instanceof Map<?, ?> rmap)) {
                log.warn("ModelQuirks: rule #{} is not a map — skipped", index);
                continue;
            }
            Object matchRaw = rmap.get("match");
            if (matchRaw == null || matchRaw.toString().isBlank()) {
                log.warn("ModelQuirks: rule #{} missing match — skipped", index);
                continue;
            }
            String pattern = matchRaw.toString().trim();
            Object parserRaw = rmap.get("messageParser");
            String parser = parserRaw == null ? null : parserRaw.toString().trim();
            if (parser != null && parser.isEmpty()) {
                parser = null;
            }
            Object tokenParamRaw = rmap.get("outputTokenParam");
            OutputTokenParam tokenParam =
                    OutputTokenParam.fromYaml(tokenParamRaw == null ? null : tokenParamRaw.toString());
            if (tokenParamRaw != null && tokenParam == null) {
                log.warn("ModelQuirks: rule #{} has unknown outputTokenParam '{}' — ignored "
                                + "(expected max_tokens / max_completion_tokens)",
                        index, tokenParamRaw);
            }
            Set<SamplingParam> unsupported = readSamplingParams(
                    rmap.get("unsupportedParams"), index);
            Object reasoningOffRaw = rmap.get("reasoningEffortWhenOff");
            String reasoningOff = reasoningOffRaw == null ? null : reasoningOffRaw.toString().trim();
            if (reasoningOff != null && reasoningOff.isEmpty()) {
                reasoningOff = null;
            }
            if (parser == null && tokenParam == null && unsupported == null
                    && reasoningOff == null) {
                log.warn("ModelQuirks: rule #{} ('{}') carries no quirk field — skipped",
                        index, pattern);
                continue;
            }
            out.add(new Rule(pattern, parser, tokenParam, unsupported, reasoningOff,
                    globToRegex(pattern)));
        }
        return List.copyOf(out);
    }

    /**
     * Translate a simple glob ({@code *}, {@code ?}) into a
     * case-insensitive anchored regex. Other regex metacharacters are
     * quoted; we deliberately do <i>not</i> support character classes —
     * the file format stays human-trivial.
     */
    private static Pattern globToRegex(String glob) {
        StringBuilder sb = new StringBuilder(glob.length() + 4);
        sb.append('^');
        for (int i = 0; i < glob.length(); i++) {
            char c = glob.charAt(i);
            switch (c) {
                case '*' -> sb.append(".*");
                case '?' -> sb.append('.');
                default -> {
                    if ("\\.^$|+(){}[]".indexOf(c) >= 0) sb.append('\\');
                    sb.append(c);
                }
            }
        }
        sb.append('$');
        return Pattern.compile(sb.toString(), Pattern.CASE_INSENSITIVE);
    }

    /**
     * Parse a rule's {@code unsupportedParams} list. {@code null} means
     * the rule doesn't speak about sampling at all; a list whose entries
     * are all unrecognised collapses to {@code null} too, so a typo
     * doesn't quietly turn into "everything is supported".
     */
    private static @Nullable Set<SamplingParam> readSamplingParams(
            @Nullable Object raw, int index) {
        if (raw == null) return null;
        if (!(raw instanceof List<?> list)) {
            log.warn("ModelQuirks: rule #{} has non-list unsupportedParams '{}' — ignored",
                    index, raw);
            return null;
        }
        Set<SamplingParam> out = EnumSet.noneOf(SamplingParam.class);
        for (Object entry : list) {
            SamplingParam parsed = SamplingParam.fromYaml(
                    entry == null ? null : entry.toString());
            if (parsed == null) {
                log.warn("ModelQuirks: rule #{} has unknown unsupportedParams entry '{}' "
                        + "— ignored", index, entry);
                continue;
            }
            out.add(parsed);
        }
        return out.isEmpty() ? null : Set.copyOf(out);
    }

    private record Rule(
            String glob,
            @Nullable String messageParser,
            @Nullable OutputTokenParam outputTokenParam,
            @Nullable Set<SamplingParam> unsupportedParams,
            @Nullable String reasoningEffortWhenOff,
            Pattern compiled) {}
}
