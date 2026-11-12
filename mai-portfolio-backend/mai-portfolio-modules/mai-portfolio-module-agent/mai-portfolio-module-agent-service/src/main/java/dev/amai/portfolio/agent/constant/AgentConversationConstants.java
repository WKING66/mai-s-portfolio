package dev.amai.portfolio.agent.constant;

public final class AgentConversationConstants {
    public static final String API_PATH = "/api/agent/v1";
    public static final String IDENTIFIER_PATTERN =
            "(?i)[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";
    public static final int CONVERSATION_PAGE_SIZE = 20;
    public static final int CONVERSATION_TURN_LIMIT = 100;
    public static final int HISTORY_TURN_LIMIT = 6;
    public static final int IMPORT_TURN_LIMIT = 20;
    public static final int MESSAGE_LENGTH_LIMIT = 4000;
    public static final int ACCEPTANCE_WINDOW_SECONDS = 45;
    public static final int RECOVERY_BATCH_SIZE = 12;
    public static final long RECOVERY_DELAY_MILLIS = 10_000;

    public static final String ROLE_USER = "user";
    public static final String ROLE_ASSISTANT = "assistant";
    public static final String SSE_EVENT_PREFIX = "event: ";
    public static final String SSE_DATA_PREFIX = "data: ";
    public static final String EVENT_ACCEPTED = "accepted";
    public static final String EVENT_SNAPSHOT = "snapshot";
    public static final String EVENT_TERMINAL = "terminal";
    public static final String EVENT_DELTA = "delta";
    public static final String EVENT_SOURCES = "sources";
    public static final String ACCEPTANCE_EXPIRED = "ACCEPTANCE_EXPIRED";
    public static final String EXECUTION_NOT_FOUND = "EXECUTION_NOT_FOUND";
    public static final String EXECUTION_TEXT_EXPIRED = "EXECUTION_TEXT_EXPIRED";

    private AgentConversationConstants() {}
}
