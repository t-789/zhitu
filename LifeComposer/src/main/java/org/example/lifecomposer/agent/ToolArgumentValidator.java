package org.example.lifecomposer.agent;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Lightweight validation of model-supplied tool arguments against the tool's own
 * JSON Schema. Only the keywords the registered tools actually use are supported:
 * {@code type}, {@code properties}, {@code required}, {@code enum},
 * {@code additionalProperties:false} and {@code items}.
 *
 * <p>Problems are reported in Chinese with the allowed values, so the model can
 * correct itself on the next round instead of silently getting an empty result.
 * Explicit JSON {@code null} on an optional property is treated as "not provided",
 * matching {@link AgentToolSupport}.</p>
 */
final class ToolArgumentValidator {

    private static final int MAX_ERRORS = 8;

    private ToolArgumentValidator() {
    }

    /** @return human-readable problems, empty when the arguments are valid. */
    static List<String> validate(Map<String, Object> schema, JsonObject arguments) {
        List<String> errors = new ArrayList<>();
        if (schema != null) {
            validateObject(schema, arguments, "", errors);
        }
        return errors;
    }

    private static void validateObject(Map<String, Object> schema, JsonObject value,
                                       String path, List<String> errors) {
        Map<String, Object> properties = asMap(schema.get("properties"));

        if (schema.get("required") instanceof List<?> required) {
            for (Object name : required) {
                String key = String.valueOf(name);
                if (!value.has(key) || value.get(key).isJsonNull()) {
                    add(errors, "缺少必填参数 " + join(path, key));
                }
            }
        }

        if (Boolean.FALSE.equals(schema.get("additionalProperties")) && properties != null) {
            for (String key : value.keySet()) {
                if (!properties.containsKey(key)) {
                    add(errors, "未知参数 " + join(path, key)
                            + (properties.isEmpty() ? "（该工具不接受参数）" : "，允许的参数：" + properties.keySet()));
                }
            }
        }

        if (properties == null) {
            return;
        }
        for (Map.Entry<String, Object> entry : properties.entrySet()) {
            JsonElement child = value.get(entry.getKey());
            Map<String, Object> childSchema = asMap(entry.getValue());
            if (child == null || child.isJsonNull() || childSchema == null) {
                continue;
            }
            validateValue(childSchema, child, join(path, entry.getKey()), errors);
        }
    }

    private static void validateValue(Map<String, Object> schema, JsonElement value,
                                      String path, List<String> errors) {
        Object type = schema.get("type");
        if (type instanceof String t && !matchesType(t, value)) {
            add(errors, "参数 " + path + " 类型必须是 " + typeName(t) + "，收到 " + describe(value));
            return;
        }

        if (schema.get("enum") instanceof List<?> allowed && value.isJsonPrimitive()) {
            String actual = value.getAsString();
            boolean found = allowed.stream().anyMatch(item -> String.valueOf(item).equals(actual));
            if (!found) {
                add(errors, "参数 " + path + " 取值必须是 " + allowed + " 之一，收到 \"" + actual + "\"");
            }
        }

        if (value.isJsonObject()) {
            validateObject(schema, value.getAsJsonObject(), path, errors);
        } else if (value.isJsonArray() && asMap(schema.get("items")) != null) {
            Map<String, Object> itemSchema = asMap(schema.get("items"));
            JsonArray array = value.getAsJsonArray();
            for (int i = 0; i < array.size(); i++) {
                JsonElement item = array.get(i);
                String itemPath = path + "[" + i + "]";
                if (item == null || item.isJsonNull()) {
                    add(errors, "参数 " + itemPath + " 不能为空");
                } else {
                    validateValue(itemSchema, item, itemPath, errors);
                }
            }
        }
    }

    private static boolean matchesType(String type, JsonElement value) {
        return switch (type) {
            case "string" -> value.isJsonPrimitive() && value.getAsJsonPrimitive().isString();
            case "boolean" -> value.isJsonPrimitive() && value.getAsJsonPrimitive().isBoolean();
            case "number" -> isNumber(value);
            case "integer" -> isNumber(value) && isWhole(value.getAsJsonPrimitive());
            case "array" -> value.isJsonArray();
            case "object" -> value.isJsonObject();
            default -> true;
        };
    }

    private static boolean isNumber(JsonElement value) {
        return value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber();
    }

    private static boolean isWhole(JsonPrimitive number) {
        try {
            return number.getAsBigDecimal().stripTrailingZeros().scale() <= 0;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private static String typeName(String type) {
        return switch (type) {
            case "string" -> "字符串";
            case "integer" -> "整数";
            case "number" -> "数字";
            case "boolean" -> "布尔值";
            case "array" -> "数组";
            case "object" -> "对象";
            default -> type;
        };
    }

    private static String describe(JsonElement value) {
        if (value.isJsonArray()) {
            return "数组";
        }
        if (value.isJsonObject()) {
            return "对象";
        }
        JsonPrimitive primitive = value.getAsJsonPrimitive();
        if (primitive.isString()) {
            String s = primitive.getAsString();
            return "字符串 \"" + (s.length() > 40 ? s.substring(0, 40) + "…" : s) + "\"";
        }
        if (primitive.isBoolean()) {
            return "布尔值 " + primitive.getAsBoolean();
        }
        return "数字 " + new BigDecimal(primitive.getAsString()).toPlainString();
    }

    private static String join(String path, String key) {
        return path.isEmpty() ? key : path + "." + key;
    }

    private static void add(List<String> errors, String message) {
        if (errors.size() < MAX_ERRORS) {
            errors.add(message);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return value instanceof Map<?, ?> ? (Map<String, Object>) value : null;
    }
}
