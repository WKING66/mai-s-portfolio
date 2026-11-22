package dev.amai.portfolio.agent.component;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.amai.portfolio.agent.controller.AgentConversationController;
import dev.amai.portfolio.agent.entity.AgentTurn;
import dev.amai.portfolio.agent.service.AgentConversationService;
import dev.amai.portfolio.web.advice.GlobalExceptionHandler;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import tools.jackson.databind.json.JsonMapper;

class AgentStreamExecutorTest {
    private static final String FIRST_ID = "46abe276-08b6-4d59-8d3d-7822920dcfac";
    private static final String SECOND_ID = "666d32c3-1649-4b4f-8537-bdfb2fb58cf8";

    @Test
    void saturatedNewSubmissionsDoNotConsumeQuotaOrBlockResumeOrExport() throws Exception {
        var service = mock(AgentConversationService.class);
        var owners = mock(AgentConversationOwnerResolver.class);
        when(owners.resolve(any(), eq(null))).thenReturn(new AgentConversationOwnerResolver.Owner("user:1", false));
        var active = mock(AgentTurn.class);
        var resumed = mock(AgentTurn.class);
        when(service.submit(42L, "user:1", false, FIRST_ID, "问题")).thenReturn(active);
        when(service.receipt(active)).thenReturn(new AgentConversationService.Receipt(FIRST_ID, "23", "accepted"));
        when(service.ownedExecution(23L, "user:1")).thenReturn(resumed);
        var forwarding = new CountDownLatch(1);
        var finish = new CountDownLatch(1);
        doAnswer(call -> {
            assertTrue(Thread.currentThread().getName().startsWith("agent-sse-"));
            forwarding.countDown();
            assertTrue(finish.await(10, TimeUnit.SECONDS));
            return null;
        }).when(service).events(eq(active), any(), any());

        var streams = new AgentStreamExecutor(1, 1);
        try {
            var mvc = MockMvcBuilders.standaloneSetup(
                            new AgentConversationController(service, owners, streams, JsonMapper.builder().build()),
                            new ExportFixture())
                    .setControllerAdvice(new GlobalExceptionHandler()).build();
            var first = mvc.perform(submission(FIRST_ID)).andExpect(request().asyncStarted()).andReturn();
            assertTrue(forwarding.await(5, TimeUnit.SECONDS));
            var second = mvc.perform(submission(SECOND_ID)).andExpect(request().asyncStarted()).andReturn();
            mvc.perform(asyncDispatch(second)).andExpect(status().isTooManyRequests())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                    .andExpect(jsonPath("$.code").value("CHAT_STREAM_BUSY"))
                    .andExpect(header().string("Retry-After", "2"));
            verify(service, never()).submit(42L, "user:1", false, SECOND_ID, "问题");

            var resume = mvc.perform(get("/api/agent/v1/executions/23/events")
                            .accept(MediaType.TEXT_EVENT_STREAM))
                    .andExpect(request().asyncStarted()).andReturn();
            mvc.perform(asyncDispatch(resume)).andExpect(status().isOk());
            verify(service).events(eq(resumed), any(), any());

            var export = mvc.perform(get("/test-export")).andExpect(request().asyncStarted()).andReturn();
            mvc.perform(asyncDispatch(export)).andExpect(status().isOk())
                    .andExpect(content().string("export"));
            finish.countDown();
            mvc.perform(asyncDispatch(first)).andExpect(status().isOk())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM))
                    .andExpect(content().string(containsString("event: accepted")));
        } finally {
            finish.countDown();
            streams.close();
        }
    }

    private static MockHttpServletRequestBuilder submission(String requestId) {
        return post("/api/agent/v1/conversations/42/turns")
                .accept(MediaType.TEXT_EVENT_STREAM)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"requestId\":\"" + requestId + "\",\"question\":\"问题\"}");
    }

    @RestController
    static class ExportFixture {
        @GetMapping("/test-export")
        StreamingResponseBody export() {
            return output -> {
                assertFalse(Thread.currentThread().getName().startsWith("agent-sse-"));
                output.write("export".getBytes(StandardCharsets.UTF_8));
            };
        }
    }
}
