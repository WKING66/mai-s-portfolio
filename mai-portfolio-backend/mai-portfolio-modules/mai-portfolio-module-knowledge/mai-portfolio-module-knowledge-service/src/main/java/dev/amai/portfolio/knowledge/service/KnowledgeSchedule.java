package dev.amai.portfolio.knowledge.service;

import dev.amai.portfolio.knowledge.api.KnowledgeBuildMode;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class KnowledgeSchedule {
    private final ZoneId zone;
    private final LocalTime start;
    private final LocalTime end;

    public KnowledgeSchedule(@Value("${knowledge.off-peak.zone:Asia/Shanghai}") String zone,
            @Value("${knowledge.off-peak.start:02:00}") String start,
            @Value("${knowledge.off-peak.end:05:00}") String end) {
        this.zone = ZoneId.of(zone);
        this.start = LocalTime.parse(start);
        this.end = LocalTime.parse(end);
        if (this.start.equals(this.end)) {
            throw new IllegalArgumentException("Knowledge off-peak start and end must differ");
        }
    }

    public Instant due(Instant now, int mode) {
        if (mode != KnowledgeBuildMode.OFF_PEAK.code()) {
            return now;
        }
        var date = now.atZone(zone).toLocalDate();
        // 同时检查前一天开始的跨午夜窗口，按 Instant 比较窗口边界。
        for (int day = -1; day <= 2; day++) {
            var windowDate = date.plusDays(day);
            var from = windowDate.atTime(start).atZone(zone).toInstant();
            var until = windowDate.plusDays(start.isAfter(end) ? 1 : 0).atTime(end).atZone(zone).toInstant();
            if (from.isBefore(until) && now.isBefore(until)) {
                return now.isBefore(from) ? from : now;
            }
        }
        throw new IllegalStateException("No knowledge indexing window found");
    }

    public Instant retry(Instant now, int attempts, int mode) {
        long seconds = Math.min(15L << (attempts - 1), 900L);
        return due(now.plusSeconds(seconds + ThreadLocalRandom.current().nextInt(4)), mode);
    }
}
