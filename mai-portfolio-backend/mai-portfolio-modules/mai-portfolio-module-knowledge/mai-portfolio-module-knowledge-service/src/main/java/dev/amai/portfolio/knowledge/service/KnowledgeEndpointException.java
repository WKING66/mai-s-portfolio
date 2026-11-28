package dev.amai.portfolio.knowledge.service;

public class KnowledgeEndpointException extends RuntimeException {
    private final int status;
    private final String code;

    public KnowledgeEndpointException(int status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public int status() {
        return status;
    }

    public String code() {
        return code;
    }

    public static KnowledgeEndpointException staleLease() {
        return new KnowledgeEndpointException(409, "LEASE_EXPIRED_OR_SUPERSEDED", "Lease expired or was superseded");
    }
}
