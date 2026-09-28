package dev.amai.portfolio.advice;

import dev.amai.portfolio.config.ApiLogProperties;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpEntity;
import org.springframework.stereotype.Component;
import org.springframework.validation.BindingResult;
import org.springframework.web.multipart.MultipartFile;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** 把入参/出参复制为日志专用 JSON 树，再递归遮盖敏感字段；绝不修改业务对象。 */
@Component
public class SensitivePayloadSanitizer {
    private static final Logger LOG = LoggerFactory.getLogger(SensitivePayloadSanitizer.class);
    private static final String REDACTED = "[REDACTED]";
    private static final String OMITTED = "[omitted]";
    private static final int MAX_DEPTH = 32;
    private static final Set<String> SENSITIVE_NAMES = Set.of(
        "password", "passwordhash", "token", "authorization", "cookie", "secret",
        "credential", "apikey", "accesskey", "privatekey", "publickey", "sessionid", "challengeid", "email",
        "markdown", "contentmarkdown", "markdownsource", "content", "html", "body");

    private final JsonMapper json;
    private final ApiLogProperties properties;
    private final Set<String> additionalSensitiveNames;

    public SensitivePayloadSanitizer(JsonMapper json, ApiLogProperties properties) {
        this.json = json;
        this.properties = properties;
        this.additionalSensitiveNames = properties.additionalSensitiveFields().stream()
            .map(SensitivePayloadSanitizer::normalize).collect(Collectors.toUnmodifiableSet());
    }

    public String sanitize(String fieldName, Object value) {
        if (isSensitive(fieldName)) {
            return REDACTED;
        }
        // 文件、Servlet 基础设施和流既可能含敏感字节，也不适合被 JSON 序列化。
        if (value instanceof byte[] || value instanceof InputStream || value instanceof OutputStream
                || value instanceof ServletRequest || value instanceof ServletResponse
                || value instanceof MultipartFile || value instanceof Resource
                || value instanceof BindingResult || value instanceof HttpEntity<?>
                || value instanceof Throwable) {
            return OMITTED;
        }
        try {
            JsonNode safeCopy = json.valueToTree(value);
            redact(safeCopy, 0);
            String serialized = json.writeValueAsString(safeCopy);
            int maxLength = properties.maxPayloadLength();
            return serialized.length() <= maxLength
                ? serialized : serialized.substring(0, maxLength) + "...[truncated]";
        } catch (RuntimeException serializationFailure) {
            // 脱敏/序列化失败时必须 fail closed；日志功能不能改变接口结果或泄露原对象。
            LOG.warn("API payload logging omitted, payloadType={}",
                value == null ? "null" : value.getClass().getName());
            return OMITTED;
        }
    }

    private void redact(JsonNode node, int depth) {
        if (depth >= MAX_DEPTH) {
            return;
        }
        if (node instanceof ObjectNode object) {
            for (var field : object.properties()) {
                if (isSensitive(field.getKey())) {
                    object.put(field.getKey(), REDACTED);
                } else if (depth + 1 >= MAX_DEPTH) {
                    object.put(field.getKey(), OMITTED);
                } else {
                    redact(field.getValue(), depth + 1);
                }
            }
        } else if (node instanceof ArrayNode array) {
            for (int index = 0; index < array.size(); index++) {
                if (depth + 1 >= MAX_DEPTH) {
                    array.set(index, OMITTED);
                } else {
                    redact(array.get(index), depth + 1);
                }
            }
        }
    }

    private boolean isSensitive(String fieldName) {
        String name = normalize(fieldName);
        if (additionalSensitiveNames.contains(name)) {
            return true;
        }
        return SENSITIVE_NAMES.stream().anyMatch(name::contains);
    }

    private static String normalize(String name) {
        return name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }
}
