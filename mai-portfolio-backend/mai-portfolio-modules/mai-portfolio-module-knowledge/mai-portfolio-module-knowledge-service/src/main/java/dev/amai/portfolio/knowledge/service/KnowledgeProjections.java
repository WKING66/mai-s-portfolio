package dev.amai.portfolio.knowledge.service;

import dev.amai.portfolio.blog.api.BlogKnowledgeSource;
import dev.amai.portfolio.document.api.DocumentKnowledgeSource;
import dev.amai.portfolio.knowledge.api.KnowledgeSourceType;
import dev.amai.portfolio.portfolio.api.ProfileKnowledgeSource;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
public class KnowledgeProjections {
    private static final Comparator<String> UTF8_ORDER = (left, right) ->
            Arrays.compareUnsigned(left.getBytes(StandardCharsets.UTF_8), right.getBytes(StandardCharsets.UTF_8));

    private static final Pattern URL_START = Pattern.compile("(?i)https?://");
    private static final Pattern SIGNED_PARAM = Pattern.compile(
            "(?i)(?:^|&(?:amp;)?)(?:x-oss-signature|ossaccesskeyid|x-amz-signature|signature)=[^&]");

    public record Projection(
            byte[] hash,

            Map<String, Object> fields) {
        public String hex() {
            return HexFormat.of().formatHex(hash);
        }
    }

    private final DocumentKnowledgeSource documentKnowledgeSource;
    private final BlogKnowledgeSource blogKnowledgeSource;
    private final ProfileKnowledgeSource profileKnowledgeSource;

    public KnowledgeProjections(DocumentKnowledgeSource documentKnowledgeSource, BlogKnowledgeSource blogKnowledgeSource,
                                ProfileKnowledgeSource profileKnowledgeSource) {
        this.documentKnowledgeSource = documentKnowledgeSource;
        this.blogKnowledgeSource = blogKnowledgeSource;
        this.profileKnowledgeSource = profileKnowledgeSource;
    }

    public Projection read(KnowledgeSourceType type, long id) {
        if (type == KnowledgeSourceType.DOCUMENT) {
            var access = documentKnowledgeSource.access(id);
            return access == null || !access.indexable() ? null
                    : document(documentKnowledgeSource.formal(id), blogKnowledgeSource.metadata(id));
        }
        return profile(profileKnowledgeSource.profile(id));
    }

    static Projection document(DocumentKnowledgeSource.Formal formal, BlogKnowledgeSource.Metadata blog) {
        if (formal == null || formal.title() == null || formal.markdown() == null) {
            return null;
        }
        Map<String, Object> fields = new TreeMap<>(UTF8_ORDER);
        fields.put("documentId", formal.id());
        add(fields, "title", formal.title());
        add(fields, "summary", formal.summary());
        add(fields, "markdown", formal.markdown());
        fields.put("tags", formal.tags().stream()
                .map(KnowledgeProjections::stableText)
                .distinct()
                .sorted(UTF8_ORDER)
                .toList());
        if (blog != null) {
            add(fields, "slug", blog.slug());
            if (blog.publishedAt() != null) {
                fields.put("publishedAt", blog.publishedAt().toString());
            }
        }
        return new Projection(hash(fields), Map.copyOf(fields));
    }

    static Projection profile(ProfileKnowledgeSource.Profile profile) {
        if (profile == null) {
            return null;
        }
        Map<String, Object> fields = new TreeMap<>(UTF8_ORDER);
        addProfile(fields, "displayName", profile.displayName());
        addProfile(fields, "email", profile.email());
        addProfile(fields, "githubUrl", profile.githubUrl());
        addProfile(fields, "headline", profile.headline());
        addProfile(fields, "intro", profile.intro());
        return new Projection(hash(fields), Map.copyOf(fields));
    }

    private static void addProfile(Map<String, Object> fields, String key, String value) {
        if (value != null && !value.isBlank()) {
            add(fields, key, value);
        }
    }

    private static void add(Map<String, Object> fields, String key, String value) {
        if (value != null) {
            fields.put(key, stableText(value));
        }
    }

    private static String stableText(String text) {
        // 正式正文通常使用资源占位符。外部 Markdown 也可能粘贴签名链接，投影中移除该链接。
        var urls = URL_START.matcher(text);
        var params = SIGNED_PARAM.matcher(text);
        StringBuilder result = null;
        int copied = 0;
        int searchFrom = 0;
        while (searchFrom < text.length() && urls.find(searchFrom)) {
            int start = urls.start();
            int bodyStart = urls.end();
            int end = scanUrlEnd(text, start, bodyStart);
            if (hasSignedQuery(text, bodyStart, end, params)) {
                if (result == null) {
                    result = new StringBuilder(text.length());
                }
                result.append(text, copied, start);
                copied = end;
            }
            searchFrom = end;
        }
        if (result == null) {
            return text;
        }
        return result.append(text, copied, text.length()).toString();
    }

    private static boolean hasSignedQuery(String text, int bodyStart, int end, Matcher params) {
        // 片段可以包含问号和参数样式的文本，签名只在片段之前的查询段中识别。
        int fragmentStart = text.indexOf('#', bodyStart, end);
        int queryEnd = fragmentStart < 0 ? end : fragmentStart;
        int queryStart = text.indexOf('?', bodyStart, queryEnd);
        return queryStart > bodyStart && params.region(queryStart + 1, queryEnd).find();
    }

    private static int scanUrlEnd(String text, int start, int bodyStart) {
        int len = text.length();
        if (startsAngleDestination(text, start)) {
            int end = bodyStart;
            while (end < len) {
                if (isMarkdownEscape(text, end)) {
                    end += 2;
                    continue;
                }
                char ch = text.charAt(end);
                if (ch == '>') {
                    return end;
                }
                if (Character.isWhitespace(ch) || ch == '<' || ch == '"' || ch == '\'') {
                    break;
                }
                end++;
            }
        }
        int end = bodyStart;
        int parenDepth = 0;
        int hostStart = bodyStart;
        boolean bracketedHost = false;
        while (end < len) {
            boolean escaped = isMarkdownEscape(text, end);
            int next = end + (escaped ? 2 : 1);
            char ch = text.charAt(next - 1);
            if (!escaped) {
                if (Character.isWhitespace(ch) || ch == '<' || ch == '>' || ch == '"' || ch == '\''
                        || (ch == ']' && !bracketedHost)) {
                    break;
                }
                if (ch == '(') {
                    parenDepth++;
                } else if (ch == ')') {
                    if (parenDepth == 0) {
                        break;
                    }
                    parenDepth--;
                }
            }

            // 主机从协议后或用户信息分隔符后开始，只有该处开启的方括号可跨过右括号。
            if (hostStart >= 0) {
                if (ch == '[' && end == hostStart) {
                    bracketedHost = true;
                } else if (ch == '@' && !bracketedHost) {
                    hostStart = next;
                } else if (ch == '/' || ch == '?' || ch == '#' || ch == ']') {
                    hostStart = -1;
                    bracketedHost = false;
                }
            }
            end = next;
        }
        return end;
    }

    private static boolean startsAngleDestination(String text, int start) {
        int angle = start - 1;
        if (angle < 0 || text.charAt(angle) != '<') {
            return false;
        }
        // 转义的左尖括号不能开启新一轮尖括号扫描，避免失败回退时反复扫描同一段文本。
        int escapeStart = angle;
        while (escapeStart > 0 && text.charAt(escapeStart - 1) == '\\') {
            escapeStart--;
        }
        return (angle - escapeStart) % 2 == 0;
    }

    private static boolean isMarkdownEscape(String text, int index) {
        if (text.charAt(index) != '\\' || index + 1 == text.length()) {
            return false;
        }
        char next = text.charAt(index + 1);
        return (next >= '!' && next <= '/') || (next >= ':' && next <= '@')
                || (next >= '[' && next <= '`') || (next >= '{' && next <= '~');
    }

    static byte[] hash(Map<String, Object> fields) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(canonical(fields).getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-256 is required", failure);
        }
    }

    static String canonical(Map<String, Object> fields) {
        var json = new StringBuilder();
        append(json, fields);
        return json.toString();
    }

    private static void append(StringBuilder json, Object value) {
        switch (value) {
            case Map<?, ?> fields -> {
                var ordered = new TreeMap<String, Object>(UTF8_ORDER);
                fields.forEach((key, field) -> {
                    if (field != null) {
                        ordered.put((String) key, field);
                    }
                });
                json.append('{');
                boolean first = true;
                for (var entry : ordered.entrySet()) {
                    if (!first) {
                        json.append(',');
                    }
                    first = false;
                    quote(json, entry.getKey());
                    json.append(':');
                    append(json, entry.getValue());
                }
                json.append('}');
            }
            case List<?> items -> {
                json.append('[');
                for (int index = 0; index < items.size(); index++) {
                    if (index != 0) {
                        json.append(',');
                    }
                    quote(json, (String) items.get(index));
                }
                json.append(']');
            }
            case String text -> quote(json, text);
            case Integer integer -> json.append(integer);
            case Long integer -> json.append(integer);
            case Boolean bool -> json.append(bool);
            default -> throw new IllegalArgumentException("Invalid knowledge projection value");
        }
    }

    private static void quote(StringBuilder json, String text) {
        json.append('"');
        for (int index = 0; index < text.length(); index++) {
            char character = text.charAt(index);
            switch (character) {
                case '"' -> json.append("\\\"");
                case '\\' -> json.append("\\\\");
                case '\b' -> json.append("\\b");
                case '\f' -> json.append("\\f");
                case '\n' -> json.append("\\n");
                case '\r' -> json.append("\\r");
                case '\t' -> json.append("\\t");
                default -> {
                    if (character < 32) {
                        json.append("\\u").append(HexFormat.of().toHexDigits(character));
                    } else {
                        json.append(character);
                    }
                }
            }
        }
        json.append('"');
    }
}
