package dev.amai.portfolio.knowledge.service;

import static dev.amai.portfolio.knowledge.api.KnowledgeSourceType.DOCUMENT;

import dev.amai.portfolio.blog.api.BlogKnowledgeSource;
import dev.amai.portfolio.document.api.DocumentKnowledgeSource;
import dev.amai.portfolio.document.enums.DocumentVisibilityLevel;
import dev.amai.portfolio.knowledge.api.KnowledgeSourceChanged;
import dev.amai.portfolio.knowledge.api.KnowledgeSyncAction;
import dev.amai.portfolio.knowledge.mapper.KnowledgeQueueMapper;
import dev.amai.portfolio.portfolio.api.ProfileKnowledgeSource;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class CachedDocumentAuthSnapshot {
    public record Candidate(
            int sourceType,

            long sourceId,

            long changeSeq) {}

    public record Decision(
            int sourceType,

            long sourceId,

            long changeSeq,

            boolean authorized,

            Long currentChangeSeq,

            String title,

            String slug,

            Integer visibilityLevel) {}

    private record Access(
            boolean permitted,

            String title,

            String slug,

            DocumentVisibilityLevel visibilityLevel) {}

    private record Entry(
            Access access,

            Long seq) {}

    private record Snapshot(
            Map<KnowledgeSyncService.Key, Entry> entries,

            Instant expiresAt) {}

    private final KnowledgeQueueMapper knowledgeQueueMapper;
    private final DocumentKnowledgeSource documentKnowledgeSource;
    private final BlogKnowledgeSource blogKnowledgeSource;
    private final ProfileKnowledgeSource profileKnowledgeSource;
    private final TransactionTemplate transaction;
    private final Duration ttl;
    private final Object lock = new Object();
    private volatile long revision;
    private volatile Snapshot cached;

    public CachedDocumentAuthSnapshot(KnowledgeQueueMapper knowledgeQueueMapper, DocumentKnowledgeSource documentKnowledgeSource,
            BlogKnowledgeSource blogKnowledgeSource, ProfileKnowledgeSource profileKnowledgeSource, PlatformTransactionManager manager,
            @Value("${knowledge.auth.cache-ttl:PT12H}") Duration ttl) {
        this.knowledgeQueueMapper = knowledgeQueueMapper;
        this.documentKnowledgeSource = documentKnowledgeSource;
        this.blogKnowledgeSource = blogKnowledgeSource;
        this.profileKnowledgeSource = profileKnowledgeSource;
        this.ttl = ttl;
        if (ttl.isNegative()) {
            throw new IllegalArgumentException("Knowledge authorization cache TTL must be nonnegative");
        }
        transaction = new TransactionTemplate(manager);
        transaction.setReadOnly(true);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public List<Decision> validate(List<Candidate> candidates) {
        var entries = snapshot().entries();
        return candidates.stream()
                .map(item -> decision(item, entries))
                .toList();
    }

    private Decision decision(Candidate item, Map<KnowledgeSyncService.Key, Entry> entries) {
        var entry = entries.get(new KnowledgeSyncService.Key(item.sourceType(), item.sourceId()));
        var access = entry == null ? null : entry.access();
        var seq = entry == null ? null : entry.seq();
        boolean authorized = access != null && access.permitted() && seq != null && seq == item.changeSeq();
        return new Decision(item.sourceType(), item.sourceId(), item.changeSeq(), authorized, seq,
                authorized ? access.title() : null, authorized ? access.slug() : null,
                access == null ? null : access.visibilityLevel().code());
    }

    private Snapshot snapshot() {
        while (true) {
            var local = cached;
            if (local != null && local.expiresAt().isAfter(Instant.now())) {
                return local;
            }
            long observed = revision;
            Instant started = Instant.now();
            var loaded = transaction.execute(status -> load(started));
            synchronized (lock) {
                // 旧查询可能在下架提交后才结束。只有同一代快照可以回填。
                if (observed == revision) {
                    if (!ttl.isZero()) {
                        cached = loaded;
                    }
                    return loaded;
                }
            }
        }
    }

    private Snapshot load(Instant started) {
        var rows = knowledgeQueueMapper.all();
        var documentsById = documentKnowledgeSource.accessSnapshot().stream()
                .collect(Collectors.toMap(DocumentKnowledgeSource.Access::id, Function.identity()));
        var profilesById = profileKnowledgeSource.profiles().stream()
                .collect(Collectors.toMap(ProfileKnowledgeSource.Profile::id, Function.identity()));
        var blogsById = blogKnowledgeSource.metadataSnapshot().stream()
                .collect(Collectors.toMap(BlogKnowledgeSource.Metadata::documentId, Function.identity()));
        var formalIds = rows.stream()
                .filter(row -> {
                    var access = documentsById.get(row.sourceId());
                    return row.sourceType() == DOCUMENT.code() && row.action() == KnowledgeSyncAction.BUILD.code()
                            && access != null && access.indexable();
                })
                .map(KnowledgeQueueMapper.Row::sourceId)
                .toList();
        var formalById = documentKnowledgeSource.formalSnapshot(formalIds).stream()
                .collect(Collectors.toMap(DocumentKnowledgeSource.Formal::id, Function.identity()));

        // 只发布权限及引用元数据。正文和摘要仅用于冷加载时校验投影指纹。
        Map<KnowledgeSyncService.Key, Entry> entries = new HashMap<>();
        for (var row : rows) {
            var key = new KnowledgeSyncService.Key(row.sourceType(), row.sourceId());
            Access access = null;
            if (row.sourceType() == DOCUMENT.code()) {
                var document = documentsById.get(row.sourceId());
                var formal = formalById.get(row.sourceId());
                var blog = blogsById.get(row.sourceId());
                if (document != null) {
                    var projection = KnowledgeProjections.document(formal, blog);
                    boolean permitted = matches(row, projection);
                    access = new Access(permitted, permitted ? formal.title() : null,
                            permitted && blog != null ? blog.slug() : null, DocumentVisibilityLevel.fromCode(document.visibilityLevel()));
                }
            } else {
                var profile = profilesById.get(row.sourceId());
                if (profile != null) {
                    boolean permitted = matches(row, KnowledgeProjections.profile(profile));
                    access = new Access(permitted, permitted ? profile.displayName() : null, null, DocumentVisibilityLevel.L1);
                }
            }
            entries.put(key, new Entry(access, row.changeSeq()));
        }
        return new Snapshot(Map.copyOf(entries), started.plus(ttl));
    }

    private boolean matches(KnowledgeQueueMapper.Row row, KnowledgeProjections.Projection projection) {
        return row.action() == KnowledgeSyncAction.BUILD.code()
                && projection != null && Arrays.equals(row.projectionHash(), projection.hash());
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void invalidate(KnowledgeSourceChanged event) {
        synchronized (lock) {
            revision++;
            cached = null;
        }
    }
}
