package dev.amai.portfolio.document.api;

import dev.amai.portfolio.document.enums.DocumentPublishStatus;
import dev.amai.portfolio.document.enums.DocumentVisibilityLevel;
import java.util.List;

public interface DocumentKnowledgeSource {
    record Access(
            long id,

            int publishStatus,

            int visibilityLevel,

            boolean aiIndexEnabled) {
        public boolean indexable() {
            return publishStatus == DocumentPublishStatus.PUBLISHED.code() && aiIndexEnabled
                    && (visibilityLevel == DocumentVisibilityLevel.L1.code()
                    || visibilityLevel == DocumentVisibilityLevel.L2.code());
        }
    }

    record Formal(
            long id,

            String title,

            String summary,

            String markdown,

            List<String> tags) {}

    Access access(long id);

    boolean lock(long id);

    List<Access> accessSnapshot();

    List<Formal> formalSnapshot(List<Long> ids);

    Formal formal(long id);
}
