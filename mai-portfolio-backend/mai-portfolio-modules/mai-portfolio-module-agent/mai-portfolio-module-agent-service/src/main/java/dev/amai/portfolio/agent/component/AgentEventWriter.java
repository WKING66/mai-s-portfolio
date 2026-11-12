package dev.amai.portfolio.agent.component;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/** 将单次会话执行的事件写入浏览器响应，断开后只停止写出。 */
public final class AgentEventWriter {
    private final OutputStream output;

    private boolean disconnected;

    public AgentEventWriter(OutputStream output) {
        this.output = output;
    }

    public void write(String event, String json) {
        if (disconnected) {
            return;
        }
        try {
            output.write(("event: " + event + "\n").getBytes(StandardCharsets.UTF_8));
            for (String line : json.split("\\r\\n|\\r|\\n", -1)) {
                output.write(("data: " + line + "\n").getBytes(StandardCharsets.UTF_8));
            }
            output.write('\n');
            output.flush();
        } catch (IOException error) {
            // 浏览器离开只停止写出，继续消费本轮终态并持久化网站记录。
            disconnected = true;
        }
    }
}
