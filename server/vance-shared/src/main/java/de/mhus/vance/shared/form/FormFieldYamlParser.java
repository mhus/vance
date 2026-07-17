package de.mhus.vance.shared.form;

import de.mhus.vance.api.form.FormChoiceDto;
import de.mhus.vance.api.form.FormFieldDto;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Parses the shared {@code fields:} form-field schema out of an already
 * SnakeYAML-loaded node tree into {@link FormFieldDto} instances. This
 * is the base field grammar shared by wizards, setting-forms and
 * document-templates — {@code name}/{@code type}/{@code label}/
 * {@code help}/{@code required}/{@code defaultValue}/{@code choices}/
 * {@code rows}/{@code integerMin}/{@code integerMax}/{@code min}/
 * {@code max}/{@code item}.
 *
 * <p>Deliberately operates on {@code Object} nodes (the output of
 * {@code new Yaml().load(...)}) rather than raw text, so it carries no
 * SnakeYAML dependency and each caller keeps ownership of how the YAML
 * is loaded. Setting-Form-specific extensions ({@code bindsTo},
 * {@code showIf}, {@code writeIf}, {@code choicesFrom}) are intentionally
 * <em>not</em> parsed here — that loader adds them on top.
 *
 * <p>Malformed input throws {@link IllegalArgumentException} with a
 * path-qualified message; callers wrap it in their own parse exception.
 * All methods are static and pure.
 */
public final class FormFieldYamlParser {

    private FormFieldYamlParser() {}

    /**
     * Parses a {@code fields:} list. Returns an empty list when
     * {@code raw} is {@code null}. Each entry must be a map.
     */
    @SuppressWarnings("unchecked")
    public static List<FormFieldDto> parseFields(@Nullable Object raw, String parentPath) {
        if (raw == null) return List.of();
        if (!(raw instanceof List<?> list)) {
            throw new IllegalArgumentException("'" + parentPath + "' must be a list");
        }
        List<FormFieldDto> out = new ArrayList<>(list.size());
        for (int i = 0; i < list.size(); i++) {
            Object entry = list.get(i);
            if (!(entry instanceof Map<?, ?> entryMap)) {
                throw new IllegalArgumentException(
                        "'" + parentPath + "[" + i + "]' must be a map");
            }
            out.add(parseField((Map<String, Object>) entryMap, parentPath + "[" + i + "]"));
        }
        return List.copyOf(out);
    }

    private static FormFieldDto parseField(Map<String, Object> raw, String path) {
        String name = optionalString(raw.get("name"));
        if (name == null) {
            throw new IllegalArgumentException("'" + path + ".name' is required");
        }
        String type = optionalString(raw.get("type"));
        if (type == null) {
            throw new IllegalArgumentException("'" + path + ".type' is required");
        }
        Map<String, String> label = requiredLocalizedText(raw.get("label"), path + ".label");
        Map<String, String> help = optionalLocalizedText(raw.get("help"), path + ".help");
        boolean required = raw.get("required") instanceof Boolean b && b;
        String defaultValue = raw.get("defaultValue") == null
                ? null
                : String.valueOf(raw.get("defaultValue"));
        List<FormChoiceDto> choices = parseChoices(raw.get("choices"), path + ".choices");
        Integer rows = optionalInt(raw.get("rows"), path + ".rows");
        Integer integerMin = optionalInt(raw.get("integerMin"), path + ".integerMin");
        Integer integerMax = optionalInt(raw.get("integerMax"), path + ".integerMax");
        Integer min = optionalInt(raw.get("min"), path + ".min");
        Integer max = optionalInt(raw.get("max"), path + ".max");
        List<FormFieldDto> item = null;
        if ("repeat".equals(type)) {
            item = parseFields(raw.get("item"), path + ".item");
            if (item.isEmpty()) {
                throw new IllegalArgumentException(
                        "'" + path + ".item' must declare at least one nested field");
            }
        }
        return FormFieldDto.builder()
                .name(name)
                .type(type)
                .label(label)
                .help(help)
                .required(required)
                .defaultValue(defaultValue)
                .choices(choices)
                .rows(rows)
                .integerMin(integerMin)
                .integerMax(integerMax)
                .min(min)
                .max(max)
                .item(item)
                .build();
    }

    @SuppressWarnings("unchecked")
    private static List<FormChoiceDto> parseChoices(@Nullable Object raw, String path) {
        if (raw == null) return List.of();
        if (!(raw instanceof List<?> list)) {
            throw new IllegalArgumentException("'" + path + "' must be a list");
        }
        List<FormChoiceDto> out = new ArrayList<>(list.size());
        for (int i = 0; i < list.size(); i++) {
            Object entry = list.get(i);
            if (entry instanceof String s) {
                if (s.isBlank()) {
                    throw new IllegalArgumentException("'" + path + "[" + i + "]' is blank");
                }
                out.add(FormChoiceDto.builder().value(s).build());
                continue;
            }
            if (!(entry instanceof Map<?, ?> entryMap)) {
                throw new IllegalArgumentException(
                        "'" + path + "[" + i + "]' must be a string or a map");
            }
            Map<String, Object> m = (Map<String, Object>) entryMap;
            String value = optionalString(m.get("value"));
            if (value == null) {
                throw new IllegalArgumentException("'" + path + "[" + i + "].value' is required");
            }
            Map<String, String> label = optionalLocalizedText(m.get("label"), path + "[" + i + "].label");
            boolean def = (m.get("default") instanceof Boolean b && b)
                    || (m.get("defaultSelected") instanceof Boolean b2 && b2);
            out.add(FormChoiceDto.builder()
                    .value(value)
                    .label(label)
                    .defaultSelected(def)
                    .build());
        }
        return List.copyOf(out);
    }

    /** Parses a required {@code Map<lang, text>} entry (or plain-string shortcut). */
    public static Map<String, String> requiredLocalizedText(@Nullable Object raw, String path) {
        Map<String, String> resolved = optionalLocalizedText(raw, path);
        if (resolved == null || resolved.isEmpty()) {
            throw new IllegalArgumentException("'" + path + "' is required");
        }
        return resolved;
    }

    /**
     * Parses a {@code Map<lang, text>} entry. Also accepts a plain string
     * as a shortcut — it becomes a single-entry map under the literal key
     * {@code "en"} so downstream localization resolves it via the
     * universal-fallback rule in {@link LocalizedTexts#resolve}.
     */
    @SuppressWarnings("unchecked")
    public static @Nullable Map<String, String> optionalLocalizedText(@Nullable Object raw, String path) {
        if (raw == null) return null;
        if (raw instanceof String s) {
            if (s.isBlank()) return null;
            return Map.of("en", s);
        }
        if (!(raw instanceof Map<?, ?> rawMap)) {
            throw new IllegalArgumentException(
                    "'" + path + "' must be a string or a map of language → text");
        }
        Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : rawMap.entrySet()) {
            String lang = String.valueOf(e.getKey());
            if (lang.isBlank()) {
                throw new IllegalArgumentException("'" + path + "' contains a blank language key");
            }
            Object v = e.getValue();
            if (v == null) continue;
            if (!(v instanceof String s)) {
                throw new IllegalArgumentException("'" + path + "." + lang + "' must be a string");
            }
            if (s.isBlank()) continue;
            out.put(lang, s);
        }
        return Map.copyOf(out);
    }

    /** Returns the string when {@code raw} is a non-blank string, else {@code null}. */
    public static @Nullable String optionalString(@Nullable Object raw) {
        return raw instanceof String s && !s.isBlank() ? s : null;
    }

    /** Coerces {@code raw} to an Integer (accepts Number / numeric String); {@code null} passes through. */
    public static @Nullable Integer optionalInt(@Nullable Object raw, String path) {
        if (raw == null) return null;
        if (raw instanceof Integer i) return i;
        if (raw instanceof Long l) return l.intValue();
        if (raw instanceof Number n) return n.intValue();
        if (raw instanceof String s) {
            try {
                return Integer.parseInt(s.trim());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("'" + path + "' is not an integer: " + s);
            }
        }
        throw new IllegalArgumentException("'" + path + "' must be an integer");
    }
}
