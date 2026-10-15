package dev.amai.portfolio.blog.entity.domain;

public record DocumentSummary(
        long documentId,

        long version,

        String title,

        String summary) {}
