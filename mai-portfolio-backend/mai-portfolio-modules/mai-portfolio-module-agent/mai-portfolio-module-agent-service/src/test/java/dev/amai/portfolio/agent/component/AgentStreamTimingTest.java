package dev.amai.portfolio.agent.component;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import dev.amai.portfolio.agent.entity.AgentTurn;
import java.io.ByteArrayOutputStream;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.json.JsonMapper;

class AgentStreamTimingTest {
    @Test
    void firstBodyIgnoresUnicodeSpacesAndDoesNotLogContent() {
        var logger = (Logger) LoggerFactory.getLogger(AgentStreamTiming.class);
        var captured = new ListAppender<ILoggingEvent>();
        captured.start();
        logger.addAppender(captured);
        try {
            var response = new MockHttpServletResponse();
            var json = JsonMapper.builder().build();
            var timing = new AgentStreamTiming(new AgentEventWriter(new ByteArrayOutputStream(), response),
                    json, mock(AgentTurn.class), true, AgentStreamTiming.RequestStart.now(), System.nanoTime());

            timing.write("snapshot", json.writeValueAsString(Map.of("text", "\u00a0\u2007\u202f\u0085")));
            timing.logFinished(false);
            timing.write("delta", json.writeValueAsString(Map.of("text", "正文")));
            timing.logFinished(false);

            assertTrue(captured.list.get(0).getFormattedMessage().contains("body_received_ms=null"));
            String withBody = captured.list.get(1).getFormattedMessage();
            assertFalse(withBody.contains("body_received_ms=null"));
            assertFalse(withBody.contains("正文"));
        } finally {
            logger.detachAppender(captured);
            captured.stop();
        }
    }
}
