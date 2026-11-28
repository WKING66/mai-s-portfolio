package dev.amai.portfolio.blog.api;

import java.time.Instant;
import java.util.List;

public interface BlogKnowledgeSource {
    record Metadata(
            long documentId,

            String slug,

            Instant publishedAt) {}

    Metadata metadata(long documentId);

    List<Metadata> metadataSnapshot();
}
