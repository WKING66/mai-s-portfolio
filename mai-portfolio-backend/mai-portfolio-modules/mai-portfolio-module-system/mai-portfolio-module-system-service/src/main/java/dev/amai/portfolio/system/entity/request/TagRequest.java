package dev.amai.portfolio.system.entity.request;

import dev.amai.portfolio.system.api.TagKind;
import dev.amai.portfolio.system.enums.TechGroup;
import jakarta.validation.constraints.*;

/** 共享标签创建输入；技术分组只适用于 TECH，主题不伪装成技术能力。 */
public record TagRequest(
        @NotNull
        TagKind kind,

        @NotBlank
        @Size(max = 100)
        String name,

        @NotBlank
        @Size(max = 120)
        @Pattern(regexp = "[a-z0-9]+(?:-[a-z0-9]+)*")
        String slug,

        TechGroup group,

        @Size(max = 160)
        String logoKey) {
    @AssertTrue(message = "TECH 标签必须选择技术分组，TOPIC 标签不能指定技术分组")
    public boolean isGroupCompatible() {
        return kind == null || (kind == TagKind.TECH ? group != null : group == null);
    }
}
