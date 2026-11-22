package dev.amai.portfolio.agent.component;

import static dev.amai.portfolio.agent.constant.AgentConversationConstants.EVENT_ACCEPTED;
import static dev.amai.portfolio.agent.constant.AgentConversationConstants.EVENT_DELTA;
import static dev.amai.portfolio.agent.constant.AgentConversationConstants.EVENT_SNAPSHOT;

import dev.amai.portfolio.agent.entity.AgentTurn;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/** 诊断开启时记录单条 SSE 连接的首字耗时，不保存事件内容。 */
public final class AgentStreamTiming {
    private static final Logger LOG = LoggerFactory.getLogger(AgentStreamTiming.class);

    private final AgentEventWriter writer;
    private final JsonMapper json;
    private final AgentTurn turn;
    private final boolean submit;
    private final RequestStart requestStart;
    private final long workerStartMs;
    private final long readyMs;
    private Long acceptedFlushedMs;
    private Long bodyReceivedMs;
    private Long bodyFlushedMs;

    public AgentStreamTiming(AgentEventWriter writer, JsonMapper json, AgentTurn turn, boolean submit,
            RequestStart requestStart, long workerStart) {
        this.writer = writer;
        this.json = json;
        this.turn = turn;
        this.submit = submit;
        this.requestStart = requestStart;
        this.workerStartMs = TimeUnit.NANOSECONDS.toMillis(workerStart - requestStart.nanoTime());
        this.readyMs = elapsedMs();
    }

    public void write(String event, String data) {
        long received = elapsedMs();
        boolean firstBody = bodyReceivedMs == null && (EVENT_DELTA.equals(event) || EVENT_SNAPSHOT.equals(event))
                && containsBody(data);
        if (firstBody) {
            bodyReceivedMs = received;
        }
        writer.write(event, data);
        if (EVENT_ACCEPTED.equals(event)) {
            acceptedFlushedMs = elapsedMs();
        }
        if (firstBody) {
            bodyFlushedMs = elapsedMs();
        }
    }

    public void logFinished(boolean clientDisconnected) {
        LOG.info("chat_sse_timing request_id={} execution_id={} mode={} request_started_epoch_ms={} "
                        + "worker_start_ms={} turn_ready_ms={} "
                        + "accepted_flushed_ms={} body_received_ms={} body_flushed_ms={} stream_finished_ms={} disconnected={}",
                turn.getRequestId(), turn.getExecutionId(), submit ? "submit" : "resume", requestStart.epochMs(),
                workerStartMs, readyMs,
                acceptedFlushedMs, bodyReceivedMs, bodyFlushedMs, elapsedMs(), clientDisconnected);
    }

    private boolean containsBody(String data) {
        try {
            // Java 的 isBlank 忽略不可断空格和 U+0085。用与 Python strip 一致的口径。
            return json.readTree(data).path("text").asText().codePoints()
                    .anyMatch(codePoint -> !Character.isWhitespace(codePoint)
                            && !Character.isSpaceChar(codePoint) && codePoint != 0x85);
        } catch (JacksonException ignored) {
            // 计时解析失败时保留既有的 SSE 转发行为。
            return false;
        }
    }

    private long elapsedMs() {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - requestStart.nanoTime());
    }

    public record RequestStart(long nanoTime, long epochMs) {
        public static RequestStart now() {
            return new RequestStart(System.nanoTime(), System.currentTimeMillis());
        }
    }
}
