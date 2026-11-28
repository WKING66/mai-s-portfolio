package dev.amai.portfolio.knowledge.service;

import static dev.amai.portfolio.knowledge.api.KnowledgeSyncAction.*;
import static dev.amai.portfolio.knowledge.api.KnowledgeSyncStatus.*;

import dev.amai.portfolio.document.api.DocumentKnowledgeSource;
import dev.amai.portfolio.knowledge.api.*;
import dev.amai.portfolio.knowledge.mapper.KnowledgeQueueMapper;
import dev.amai.portfolio.knowledge.mapper.KnowledgeQueueMapper.Row;
import dev.amai.portfolio.knowledge.mapper.KnowledgeQueueMapper.Target;
import dev.amai.portfolio.portfolio.api.ProfileKnowledgeSource;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class KnowledgeSyncService implements KnowledgeSyncRegistrar {
    private static final int MAX_ATTEMPTS = 5;
    private static final int LEASE_SECONDS = 120;

    public record Key(
            int sourceType,

            long sourceId) {}

    public record Claim(
            long queueId,

            int sourceType,

            long sourceId,

            long changeSeq,

            int action,

            int buildMode,

            String projectionHash,

            String leaseToken,

            Instant leaseUntil,

            int attemptCount,

            Map<String, Object> projection) {}

    public record Claims(
            List<Claim> claims,

            Instant nextDueAt) {}

    public record ProjectionResponse(
            int sourceType,

            long sourceId,

            long changeSeq,

            String projectionHash,

            Map<String, Object> projection) {}

    public record Lease(
            int sourceType,

            long sourceId,

            long changeSeq,

            UUID leaseToken) {}

    public record Failure(
            String status,

            int attemptCount,

            Instant dueAt) {}

    public record Retry(
            long changeSeq,

            int status,

            Instant dueAt) {}

    public record Backfill(
            int scanned,

            int enqueued,

            int skipped) {}

    public record Reconcile(
            int checked,

            int mismatched,

            int repaired) {}

    private final KnowledgeQueueMapper knowledgeQueueMapper;
    private final KnowledgeProjections projections;
    private final KnowledgeSchedule schedule;
    private final ApplicationEventPublisher events;
    private final DocumentKnowledgeSource documentKnowledgeSource;
    private final ProfileKnowledgeSource profileKnowledgeSource;
    private final TransactionTemplate transaction;

    public KnowledgeSyncService(KnowledgeQueueMapper knowledgeQueueMapper, KnowledgeProjections projections,
            KnowledgeSchedule schedule, ApplicationEventPublisher events, DocumentKnowledgeSource documentKnowledgeSource,
            ProfileKnowledgeSource profileKnowledgeSource, PlatformTransactionManager transactionManager) {
        this.knowledgeQueueMapper = knowledgeQueueMapper;
        this.projections = projections;
        this.schedule = schedule;
        this.events = events;
        this.documentKnowledgeSource = documentKnowledgeSource;
        this.profileKnowledgeSource = profileKnowledgeSource;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    /**
     * 登记知识源变更对应的索引同步目标。
     * 调用方必须在已有的源记录写事务（{@code MANDATORY}）中持有源记录行锁直至事务结束，
     * 使目标投影快照和队列状态与本次源记录变更保持一致。
     *
     * @param type 知识源类型
     * @param id 源记录 ID
     * @param mode 构建任务的调度模式
     * @param purge 是否要求彻底清除向量数据
     * @param changed 源记录是否发生变更
     */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void register(KnowledgeSourceType type, long id, KnowledgeBuildMode mode, boolean purge, boolean changed) {
        // 1. 读取当前队列记录并确定目标。源不可索引时，保留物理删除或彻底清除要求（PURGE），
        // 避免后续普通下架或失去索引资格时将彻底清除降级为撤回（WITHDRAW）。
        var current = knowledgeQueueMapper.bySource(type.code(), id);
        var target = target(type, id, mode.code(), purge || isPurge(current));
        // 2. 从未入队且当前不可索引的源（如未授权文档或草稿）没有索引可撤回或清理。
        if (current == null && target.action() != BUILD.code()) {
            return;
        }
        // 3. 同类撤回或清除动作复用现有任务，避免递增 change_seq 导致重复清理向量。
        if (current != null && current.action() != BUILD.code() && target.action() == current.action()) {
            // 源记录有变更时仍发布 KnowledgeSourceChanged，提交后使授权快照缓存失效，供下次读取时刷新。
            if (changed) {
                events.publishEvent(new KnowledgeSourceChanged(type, id));
            }
            return;
        }
        // 4. 源记录或同步目标有变更时，在同一事务中写入队列并发布变更事件。
        if (changed || differs(current, target)) {
            persist(type, target, false);
        }
    }

    /** 在一个短事务内持有队列行锁并读取已提交投影，不在锁内调用 Python 或外部服务。 */
    @Transactional
    public Claims claim(List<Integer> types, int limit) {
        // 1. 先回收已过期租约，保留失败次数并安排重试或终止。
        // 对齐 PostgreSQL timestamptz 微秒精度，避免 pgJDBC 亚微秒进位导致重排后重复命中。
        var now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        for (var row : knowledgeQueueMapper.expired(now)) {
            fail(row, now, KnowledgeSyncError.LEASE_TIMEOUT.code(), false, false);
        }
        // 2. 锁定到期目标，校验调度窗口和正式正文指纹后再发出租约。
        List<Claim> result = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        outer:
        while (result.size() < limit) {
            var batch = knowledgeQueueMapper.due(now, types, limit - result.size());
            if (batch.isEmpty()) {
                break;
            }
            for (var row : batch) {
                if (!seen.add(row.id())) {
                    break outer;
                }
                // 已过离峰窗口的任务保留原目标和尝试次数，下次窗口再领取。
                var due = schedule.due(now, row.buildMode());
                if (due.isAfter(now)) {
                    knowledgeQueueMapper.update(row.id(), row.status(), due, null, null, row.attemptCount(), row.lastError());
                    continue;
                }
                var projection = readMatching(row);
                if (row.action() == BUILD.code() && projection == null) {
                    fail(row, now, KnowledgeSyncError.PROJECTION.code(), true, false);
                    continue;
                }
                var until = now.plusSeconds(LEASE_SECONDS);
                var token = UUID.randomUUID().toString();
                int attempts = row.attemptCount() + 1;
                knowledgeQueueMapper.update(row.id(), LEASED.code(), row.dueAt(), token, until, attempts, row.lastError());
                result.add(new Claim(row.id(), row.sourceType(), row.sourceId(), row.changeSeq(), row.action(),
                        row.buildMode(), projection == null ? null : projection.hex(), token, until,
                        attempts, projection == null ? null : projection.fields()));
            }
        }
        // 3. 返回本次领取结果和下一次可能推进任务的时间。
        return new Claims(List.copyOf(result), knowledgeQueueMapper.nextDue(types));
    }

    @Transactional
    public ProjectionResponse projection(long id, long seq, UUID token) {
        var row = requireLease(id, seq, token);
        var projection = readMatching(row);
        if (row.action() == BUILD.code() && projection == null) {
            throw KnowledgeEndpointException.staleLease();
        }
        return new ProjectionResponse(row.sourceType(), row.sourceId(), row.changeSeq(),
                projection == null ? null : projection.hex(), projection == null ? null : projection.fields());
    }

    @Transactional
    public Instant renew(long id, Lease lease) {
        var row = requireLease(id, lease);
        var until = Instant.now().plusSeconds(LEASE_SECONDS);
        updateLeased(row, LEASED, row.dueAt(), row.leaseToken(), until, row.lastError());
        return until;
    }

    @Transactional
    public void complete(long id, Lease lease) {
        var row = requireLease(id, lease);
        updateLeased(row, COMPLETED, row.dueAt(), null, null, null);
    }

    @Transactional
    public Failure fail(long id, Lease lease, int error, boolean permanent) {
        return fail(requireLease(id, lease), Instant.now(), error, permanent, true);
    }

    private Failure fail(Row row, Instant now, int error, boolean permanent, boolean workerReport) {
        boolean terminal = permanent || error == KnowledgeSyncError.PROJECTION.code()
                || row.attemptCount() >= MAX_ATTEMPTS;
        Instant due = terminal ? row.dueAt() : schedule.retry(now, row.attemptCount(), row.buildMode());
        var status = terminal ? TERMINAL : RETRY;
        if (workerReport) {
            updateLeased(row, status, due, null, null, error);
        } else {
            knowledgeQueueMapper.update(row.id(), status.code(), due, null, null, row.attemptCount(), error);
        }
        return new Failure(terminal ? "terminal" : "retryScheduled", row.attemptCount(), terminal ? null : due);
    }

    private void updateLeased(Row row, KnowledgeSyncStatus status, Instant dueAt, String token,
                              Instant until, Integer error) {
        // 拿锁到实际更新之间也可能过期，由 PostgreSQL 在写入时再校验租约期限。
        if (knowledgeQueueMapper.updateLeased(row, status.code(), dueAt, token, until, error) != 1) {
            throw KnowledgeEndpointException.staleLease();
        }
    }

    @Transactional
    public Retry retry(int typeCode, long id) {
        var type = type(typeCode);
        lock(type, id);
        var row = knowledgeQueueMapper.lockSource(typeCode, id);
        if (row == null) {
            throw new KnowledgeEndpointException(404, "SOURCE_NOT_QUEUED", "Knowledge source is not queued");
        }
        var target = target(type, id, row.buildMode(), isPurge(row));
        if (differs(row, target)) {
            persist(type, target, false);
            return new Retry(row.changeSeq() + 1, PENDING.code(), target.dueAt());
        }
        if (row.status() != TERMINAL.code()) {
            throw new KnowledgeEndpointException(409, "SOURCE_NOT_TERMINAL", "Knowledge source is not terminal");
        }
        knowledgeQueueMapper.update(row.id(), PENDING.code(), target.dueAt(), null, null, 0, null);
        return new Retry(row.changeSeq(), PENDING.code(), target.dueAt());
    }

    public Backfill backfill(List<Integer> types, boolean dryRun) {
        int scanned = 0;
        int enqueued = 0;
        for (int code : types) {
            var type = type(code);
            for (long id : sourceIds(type)) {
                scanned++;
                if (Boolean.TRUE.equals(transaction.execute(status -> {
                    if (!lock(type, id) || knowledgeQueueMapper.bySource(code, id) != null) {
                        return false;
                    }
                    var target = target(type, id, KnowledgeBuildMode.IMMEDIATE.code(), false);
                    return target.action() == BUILD.code() && (dryRun || persist(type, target, true));
                }))) {
                    enqueued++;
                }
            }
        }
        return new Backfill(scanned, enqueued, scanned - enqueued);
    }

    public Reconcile reconcile(List<Integer> types, List<Long> requestedIds) {
        int checked = 0;
        int repaired = 0;
        var queued = requestedIds == null ? knowledgeQueueMapper.all() : List.<Row>of();
        for (int code : types) {
            var type = type(code);
            var ids = new TreeSet<Long>();
            if (requestedIds == null) {
                ids.addAll(sourceIds(type));
                queued.stream()
                        .filter(row -> row.sourceType() == code)
                        .map(Row::sourceId)
                        .forEach(ids::add);
            } else {
                ids.addAll(requestedIds);
            }
            for (long id : ids) {
                checked++;
                if (Boolean.TRUE.equals(transaction.execute(status -> {
                    boolean exists = lock(type, id);
                    var row = knowledgeQueueMapper.lockSource(code, id);
                    int mode = row == null ? KnowledgeBuildMode.IMMEDIATE.code() : row.buildMode();
                    var target = target(type, id, mode, isPurge(row) || !exists);
                    return differs(row, target) && persist(type, target, false);
                }))) {
                    repaired++;
                }
            }
        }
        return new Reconcile(checked, repaired, repaired);
    }

    private Target target(KnowledgeSourceType type, long id, int mode, boolean purge) {
        var projection = projections.read(type, id);
        var action = projection != null ? BUILD : purge ? PURGE : WITHDRAW;
        int buildMode = action == BUILD ? mode : KnowledgeBuildMode.IMMEDIATE.code();
        return new Target(type.code(), id, projection == null ? null : projection.hash(),
                action.code(), buildMode, schedule.due(Instant.now(), buildMode));
    }

    private boolean persist(KnowledgeSourceType type, Target target, boolean insertOnly) {
        if (knowledgeQueueMapper.register(target, insertOnly) == 0) {
            return false;
        }
        events.publishEvent(new KnowledgeSourceChanged(type, target.sourceId()));
        return true;
    }

    private boolean differs(Row row, Target target) {
        return row == null ? target.action() == BUILD.code()
                : row.action() != target.action() || !Arrays.equals(row.projectionHash(), target.projectionHash());
    }

    private boolean isPurge(Row row) {
        return row != null && row.action() == PURGE.code();
    }

    private Row requireLease(long id, Lease lease) {
        var row = requireLease(id, lease.changeSeq(), lease.leaseToken());
        if (row.sourceId() != lease.sourceId() || row.sourceType() != lease.sourceType()) {
            throw KnowledgeEndpointException.staleLease();
        }
        return row;
    }

    private Row requireLease(long id, long seq, UUID token) {
        // 时间在获取行锁之后读取，避免等待期间过期的租约仍被接受。
        var row = knowledgeQueueMapper.lockLease(id);
        if (row == null || row.status() != LEASED.code() || row.changeSeq() != seq
                || !Objects.equals(row.leaseToken(), token.toString()) || !row.leaseUntil().isAfter(Instant.now())) {
            throw KnowledgeEndpointException.staleLease();
        }
        return row;
    }

    private KnowledgeProjections.Projection readMatching(Row row) {
        if (row.action() != BUILD.code()) {
            return null;
        }
        var projection = projections.read(type(row.sourceType()), row.sourceId());
        return projection != null && Arrays.equals(projection.hash(), row.projectionHash()) ? projection : null;
    }

    private boolean lock(KnowledgeSourceType type, long id) {
        return type == KnowledgeSourceType.DOCUMENT ? documentKnowledgeSource.lock(id) : profileKnowledgeSource.lock(id);
    }

    private List<Long> sourceIds(KnowledgeSourceType type) {
        return type == KnowledgeSourceType.DOCUMENT
                ? documentKnowledgeSource.accessSnapshot().stream()
                        .map(DocumentKnowledgeSource.Access::id)
                        .toList()
                : profileKnowledgeSource.profiles().stream()
                        .map(ProfileKnowledgeSource.Profile::id)
                        .toList();
    }

    private KnowledgeSourceType type(int code) {
        return code == KnowledgeSourceType.DOCUMENT.code() ? KnowledgeSourceType.DOCUMENT : KnowledgeSourceType.PROFILE;
    }
}
