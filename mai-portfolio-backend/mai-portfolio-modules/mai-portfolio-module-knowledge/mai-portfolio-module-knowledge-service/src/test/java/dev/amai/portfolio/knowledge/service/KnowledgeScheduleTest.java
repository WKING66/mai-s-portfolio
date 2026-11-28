package dev.amai.portfolio.knowledge.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class KnowledgeScheduleTest {
    private final KnowledgeSchedule schedule = new KnowledgeSchedule("Asia/Shanghai", "02:00", "05:00");

    @Test
    void missedWindowAndRetryCrossingWindowEndMoveToNextStart() {
        var nextStart = Instant.parse("2026-11-28T18:00:00Z");
        var finalSeconds = Instant.parse("2026-11-27T20:59:55Z");
        assertThat(schedule.due(finalSeconds, 2)).isEqualTo(finalSeconds);
        assertThat(schedule.retry(finalSeconds, 1, 2)).isEqualTo(nextStart);
        assertThat(schedule.due(finalSeconds.plusSeconds(5), 2)).isEqualTo(nextStart);
        assertThat(schedule.due(finalSeconds.plusSeconds(5), 1)).isEqualTo(finalSeconds.plusSeconds(5));
    }
}
