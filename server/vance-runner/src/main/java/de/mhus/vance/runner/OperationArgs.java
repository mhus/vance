package de.mhus.vance.runner;

import de.mhus.vance.runner.RunnerOperation.OperationException;
import de.mhus.vance.runner.protocol.RunnerTokens;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Reads the arguments of an operation and settles which RootDir a call may touch. */
final class OperationArgs {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final @Nullable JsonNode args;

    OperationArgs(@Nullable JsonNode args) {
        this.args = args;
    }

    /** The arguments as a plain map — the form {@code RootDirSearch} and the brain's tools share. */
    Map<String, Object> asMap() {
        if (args == null || !args.isObject()) {
            return Map.of();
        }
        return JSON.convertValue(args, new TypeReference<Map<String, Object>>() {});
    }

    /** A nested object argument as a plain map; empty when absent. */
    Map<String, Object> object(String name) throws OperationException {
        Object value = asMap().get(name);
        if (value == null) {
            return Map.of();
        }
        if (!(value instanceof Map<?, ?> map)) {
            throw new OperationException("bad-args", "'" + name + "' must be an object");
        }
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        map.forEach((k, v) -> out.put(String.valueOf(k), v));
        return out;
    }

    String text(String name) throws OperationException {
        String value = optionalText(name);
        if (value == null || value.isBlank()) {
            throw new OperationException("bad-args", "'" + name + "' is required");
        }
        return value;
    }

    /** The argument as given (blank is a value), or {@code null} when absent. */
    @Nullable
    String optionalText(String name) throws OperationException {
        JsonNode node = args == null ? null : args.get(name);
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isString()) {
            throw new OperationException("bad-args", "'" + name + "' must be a string");
        }
        return node.asString();
    }

    /** A boolean argument; absent or {@code null} is {@code false}. */
    boolean flag(String name) throws OperationException {
        JsonNode node = args == null ? null : args.get(name);
        if (node == null || node.isNull()) {
            return false;
        }
        if (!node.isBoolean()) {
            throw new OperationException("bad-args", "'" + name + "' must be true or false");
        }
        return node.asBoolean();
    }

    /** A decimal argument, or {@code fallback} when absent. */
    double decimal(String name, double fallback) throws OperationException {
        Double value = decimalOrNull(name);
        return value == null ? fallback : value;
    }

    @Nullable
    Double decimalOrNull(String name) throws OperationException {
        JsonNode node = args == null ? null : args.get(name);
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isNumber()) {
            throw new OperationException("bad-args", "'" + name + "' must be a number");
        }
        return node.asDouble();
    }

    @Nullable
    Integer integerOrNull(String name) throws OperationException {
        JsonNode node = args == null ? null : args.get(name);
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isIntegralNumber()) {
            throw new OperationException("bad-args", "'" + name + "' must be a whole number");
        }
        return node.asInt();
    }

    /** A list of whole numbers; empty when absent. */
    java.util.List<Integer> integers(String name) throws OperationException {
        JsonNode node = args == null ? null : args.get(name);
        if (node == null || node.isNull()) {
            return java.util.List.of();
        }
        if (!node.isArray()) {
            throw new OperationException("bad-args", "'" + name + "' must be a list of whole numbers");
        }
        java.util.List<Integer> out = new java.util.ArrayList<>();
        for (JsonNode element : node) {
            if (!element.isIntegralNumber() || element.asInt() < 0) {
                throw new OperationException(
                        "bad-args", "'" + name + "' must hold sizes (whole numbers, not negative)");
            }
            out.add(element.asInt());
        }
        return out;
    }

    int integer(String name, int fallback) throws OperationException {
        JsonNode node = args == null ? null : args.get(name);
        if (node == null || node.isNull()) {
            return fallback;
        }
        if (!node.isIntegralNumber()) {
            throw new OperationException("bad-args", "'" + name + "' must be a whole number");
        }
        return node.asInt();
    }

    /**
     * The RootDir a call works in. A token pinned to one RootDir ({@code root} claim)
     * allows exactly that one — an argument naming another is refused, not
     * overridden, so a mixed-up caller learns about it.
     */
    String rootDir(RunnerTokens.Claims claims) throws OperationException {
        String requested = optionalText("dirName");
        String pinned = claims.rootDir();
        if (pinned != null) {
            if (requested != null && !requested.isBlank() && !requested.equals(pinned)) {
                throw new OperationException(
                        "root-not-allowed",
                        "This call is confined to RootDir '" + pinned + "', not '" + requested + "'");
            }
            return pinned;
        }
        return text("dirName");
    }

    static String sha256Hex(String content) {
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(content.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is part of every JDK", e);
        }
    }
}
