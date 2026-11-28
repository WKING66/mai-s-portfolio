package dev.amai.portfolio.knowledge.api;

public record KnowledgeSourceChanged(
        KnowledgeSourceType sourceType,

        long sourceId) {}
