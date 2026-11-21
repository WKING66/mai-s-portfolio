package dev.amai.portfolio.agent.component;

import static dev.amai.portfolio.agent.constant.AgentConversationConstants.SSE_DATA_PREFIX;
import static dev.amai.portfolio.agent.constant.AgentConversationConstants.SSE_EVENT_PREFIX;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/** 将单次会话执行的事件写入浏览器响应，断开后只停止写出。 */
public final class AgentEventWriter {
    private final OutputStream output;

    private final HttpServletResponse response;

    private boolean disconnected;

    public AgentEventWriter(OutputStream output, HttpServletResponse response) {
        this.output = output;
        this.response = response;
    }

    public void write(String event, String json) {
        if (disconnected) {
            return;
        }
        try {
            output.write((SSE_EVENT_PREFIX + event + "\n").getBytes(StandardCharsets.UTF_8));
            for (String line : json.split("\\r\\n|\\r|\\n", -1)) {
                output.write((SSE_DATA_PREFIX + line + "\n").getBytes(StandardCharsets.UTF_8));
            }
            output.write('\n');
            // 每个事件都提交 Servlet 响应缓冲，浏览器才能在执行结束前收到正文。
            response.flushBuffer();
        } catch (IOException error) {
            // 浏览器离开只停止写出，继续消费本轮终态并持久化网站记录。
            disconnected = true;
        }
    }
}
