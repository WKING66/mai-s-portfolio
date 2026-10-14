package dev.amai.portfolio.logging;

import dev.amai.portfolio.logging.autoconfigure.ApiLogProperties;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpEntity;
import org.springframework.validation.BindingResult;
import org.springframework.web.multipart.MultipartFile;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** 把入参/出参复制为日志专用 JSON 树，再递归遮盖敏感字段；绝不修改业务对象。 */
public class SensitivePayloadSanitizer {
    private static final Logger LOG = LoggerFactory.getLogger(SensitivePayloadSanitizer.class);
    private static final String REDACTED = "[REDACTED]";
    private static final String OMITTED = "[omitted]";
    // 深度达到既有上限前，将子节点替换为省略标记，避免继续遍历任意嵌套对象。
    private static final int MAX_REDACTION_DEPTH = 32;
    private static final String TRUNCATED_SUFFIX = "...[truncated]";
    private static final Set<String> SIGNED_URL_PARAMETER_NAMES =
            Set.of(
                    "signature",
                    "ossaccesskeyid",
                    "x-oss-signature",
                    "x-oss-credential",
                    "security-token");
    private static final Set<String> BUILT_IN_SENSITIVE_FIELD_NAMES =
            Set.of(
                    "password",
                    "passwordhash",
                    "token",
                    "authorization",
                    "cookie",
                    "secret",
                    "credential",
                    "apikey",
                    "accesskey",
                    "privatekey",
                    "publickey",
                    "sessionid",
                    "challengeid",
                    "email",
                    "markdown",
                    "contentmarkdown",
                    "markdownsource",
                    "content",
                    "html",
                    "body");

    private final JsonMapper jsonMapper;
    private final ApiLogProperties apiLogProperties;
    private final Set<String> additionalSensitiveFieldNames;

    public SensitivePayloadSanitizer(JsonMapper jsonMapper, ApiLogProperties apiLogProperties) {
        this.jsonMapper = jsonMapper;
        this.apiLogProperties = apiLogProperties;
        this.additionalSensitiveFieldNames =
                apiLogProperties.additionalSensitiveFields().stream()
                        .map(SensitivePayloadSanitizer::normalizeFieldName)
                        .collect(Collectors.toUnmodifiableSet());
    }

    /**
     * 为日志生成脱敏、限长的表示；文件和流不读取，业务对象不原地修改。
     *
     * <p>复制、脱敏或序列化发生运行期异常时只返回省略标记，不输出原值或异常消息。
     *
     * @param fieldName 非 null 字段名；字段名预检查保留原异常语义，不在序列化兜底范围内
     */
    public String sanitize(String fieldName, Object value) {
        // 1. 先按字段名拒绝输出敏感值，再判断是否属于不能序列化的基础设施对象。
        if (isSensitive(fieldName)) {
            return REDACTED;
        }
        // 文件、Servlet 基础设施和流既可能含敏感字节，也不适合被 JSON 序列化。
        if (value instanceof byte[]
                || value instanceof InputStream
                || value instanceof OutputStream
                || value instanceof ServletRequest
                || value instanceof ServletResponse
                || value instanceof MultipartFile
                || value instanceof Resource
                || value instanceof BindingResult
                || value instanceof HttpEntity<?>
                || value instanceof Throwable) {
            return OMITTED;
        }
        try {
            // 2. 创建日志专用 JSON 副本，并优先隐藏根节点中的已签名地址。
            JsonNode safeCopy = jsonMapper.valueToTree(value);
            if (isSignedUrl(safeCopy)) {
                return REDACTED;
            }
            // 3. 递归修改副本；父节点在到达深度上限前省略子树，不修改原业务值。
            redactLogTree(safeCopy, 0);
            // 4. 序列化后按字符数截断，保留既有的截断后缀与返回格式。
            String serialized = jsonMapper.writeValueAsString(safeCopy);
            int maxLength = apiLogProperties.maxPayloadLength();
            return serialized.length() <= maxLength
                    ? serialized
                    : serialized.substring(0, maxLength) + TRUNCATED_SUFFIX;
        } catch (RuntimeException serializationFailure) {
            // 脱敏/序列化失败时必须 fail closed；日志功能不能改变接口结果或泄露原对象。
            LOG.warn(
                    "API payload logging omitted, payloadType={}",
                    value == null ? "null" : value.getClass().getName());
            return OMITTED;
        }
    }

    private void redactLogTree(JsonNode node, int depth) {
        if (depth >= MAX_REDACTION_DEPTH) {
            return;
        }
        if (node instanceof ObjectNode object) {
            for (var field : object.properties()) {
                if (isSensitive(field.getKey()) || isSignedUrl(field.getValue())) {
                    object.put(field.getKey(), REDACTED);
                } else if (depth + 1 >= MAX_REDACTION_DEPTH) {
                    object.put(field.getKey(), OMITTED);
                } else {
                    redactLogTree(field.getValue(), depth + 1);
                }
            }
        } else if (node instanceof ArrayNode array) {
            for (int index = 0; index < array.size(); index++) {
                if (isSignedUrl(array.get(index))) {
                    array.set(index, REDACTED);
                } else if (depth + 1 >= MAX_REDACTION_DEPTH) {
                    array.set(index, OMITTED);
                } else {
                    redactLogTree(array.get(index), depth + 1);
                }
            }
        }
    }

    /** 仅识别现有 HTTP(S) 签名参数；无法解析的普通文本继续按字段名脱敏。 */
    private boolean isSignedUrl(JsonNode value) {
        if (value == null || !value.isString()) {
            return false;
        }
        try {
            URI uri = URI.create(value.asString());
            if (!"https".equalsIgnoreCase(uri.getScheme())
                    && !"http".equalsIgnoreCase(uri.getScheme())) {
                return false;
            }
            String rawQuery = uri.getRawQuery();
            if (rawQuery == null) {
                return false;
            }
            for (String queryParameter : rawQuery.split("&")) {
                String parameterName =
                        URLDecoder.decode(queryParameter.split("=", 2)[0], StandardCharsets.UTF_8)
                                .toLowerCase(Locale.ROOT);
                if (SIGNED_URL_PARAMETER_NAMES.contains(parameterName)) {
                    return true;
                }
            }
        } catch (IllegalArgumentException invalidUrl) {
            // 普通文本不是地址；原有按字段脱敏继续生效。
        }
        return false;
    }

    private boolean isSensitive(String fieldName) {
        String name = normalizeFieldName(fieldName);
        if (additionalSensitiveFieldNames.contains(name)) {
            return true;
        }
        return BUILT_IN_SENSITIVE_FIELD_NAMES.stream().anyMatch(name::contains);
    }

    private static String normalizeFieldName(String fieldName) {
        return fieldName.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }
}
