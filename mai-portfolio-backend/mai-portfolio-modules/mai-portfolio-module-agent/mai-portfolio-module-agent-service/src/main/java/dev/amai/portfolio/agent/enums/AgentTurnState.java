package dev.amai.portfolio.agent.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import java.util.List;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/** 网站保存的问答轮次状态及对外状态名称。 */
@Getter
@RequiredArgsConstructor
public enum AgentTurnState {
    PENDING((short) 0, "pending"),
    ACCEPTED((short) 1, "accepted"),
    // V10 允许旧库保存 2。保留原来的非终态和 pending 展示语义。
    RESERVED((short) 2, "pending"),
    COMPLETED((short) 3, "completed"),
    FAILED((short) 4, "failed"),
    INTERRUPTED((short) 5, "interrupted");

    private static final List<Short> ACTIVE_CODES = List.of(
            PENDING.code, ACCEPTED.code, RESERVED.code);

    @EnumValue
    private final short code;

    private final String apiValue;

    public boolean isTerminal() {
        return !ACTIVE_CODES.contains(code);
    }

    public static List<Short> activeCodes() {
        return ACTIVE_CODES;
    }
}
