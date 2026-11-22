package dev.amai.portfolio.agent.component;

import static dev.amai.portfolio.agent.constant.AgentConversationConstants.SSE_DATA_PREFIX;
import static dev.amai.portfolio.agent.constant.AgentConversationConstants.SSE_EVENT_PREFIX;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/** 将单次会话执行的事件写入浏览器响应，断开后结束订阅。 */
public final class AgentEventWriter {
    private final OutputStream output;

    private final HttpServletResponse response;

    public AgentEventWriter(OutputStream output, HttpServletResponse response) {
        this.output = output;
        this.response = response;
    }

    public void write(String event, String json) {
        try {
            output.write((SSE_EVENT_PREFIX + event + "\n").getBytes(StandardCharsets.UTF_8));
            for (String line : json.split("\\r\\n|\\r|\\n", -1)) {
                output.write((SSE_DATA_PREFIX + line + "\n").getBytes(StandardCharsets.UTF_8));
            }
            output.write('\n');
            // 每个事件都提交 Servlet 响应缓冲，浏览器才能在执行结束前收到正文。
            response.flushBuffer();
        } catch (IOException error) {
            throw new Disconnected(error);
        }
    }

    public void keepAlive() {
        try {
            output.write(": ping\n\n".getBytes(StandardCharsets.UTF_8));
            response.flushBuffer();
        } catch (IOException error) {
            throw new Disconnected(error);
        }
    }

    public static final class Disconnected extends UncheckedIOException {
        private Disconnected(IOException error) {
            super(error);
        }
    }
}
