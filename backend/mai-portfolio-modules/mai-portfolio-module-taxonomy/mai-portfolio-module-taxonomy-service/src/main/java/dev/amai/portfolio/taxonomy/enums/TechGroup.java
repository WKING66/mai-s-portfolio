package dev.amai.portfolio.taxonomy.enums;

import dev.amai.portfolio.taxonomy.constant.TaxonomyMessageConstants;
import java.util.Arrays;

public enum TechGroup {
    LANGUAGE(0), FRAMEWORK(1), TOOL(2), INFRA(3), DATA(4);

    private final int code;

    TechGroup(int code) {
        this.code = code;
    }

    public int code() {
        return code;
    }

    public static TechGroup fromCode(int code) {
        // 未知数据库编码应暴露为内部数据问题，不能默默归入任一公开分类。
        return Arrays.stream(values()).filter(group -> group.code == code).findFirst()
            .orElseThrow(() -> new IllegalStateException(TaxonomyMessageConstants.TECH_GROUP_UNKNOWN + code));
    }
}
