package dev.amai.portfolio.knowledge.controller;

import dev.amai.portfolio.knowledge.service.CachedDocumentAuthSnapshot;
import dev.amai.portfolio.knowledge.service.KnowledgeEndpointException;
import dev.amai.portfolio.knowledge.service.KnowledgeSyncService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.method.MethodValidationException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.ServletRequestBindingException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestController
@RequestMapping(KnowledgeController.PREFIX)
public class KnowledgeController {
    public static final String PREFIX = "/internal/v1/knowledge";

    public record ClaimInput(
            @NotBlank
            String workerId,

            @Min(1)
            @Max(8)
            Integer limit,

            @Size(min = 1, max = 2)
            List<@NotNull @Min(1) @Max(2) Integer> sourceTypes) {}

    public record LeaseInput(
            @NotNull
            @Min(1)
            @Max(2)
            Integer sourceType,

            @NotNull
            @Positive
            Long sourceId,

            @NotNull
            @Positive
            Long changeSeq,

            @NotNull
            UUID leaseToken) {
        KnowledgeSyncService.Lease lease() {
            return new KnowledgeSyncService.Lease(sourceType, sourceId, changeSeq, leaseToken);
        }
    }

    public record CompleteInput(
            @NotNull
            @Min(1)
            @Max(2)
            Integer sourceType,

            @NotNull
            @Positive
            Long sourceId,

            @NotNull
            @Positive
            Long changeSeq,

            @NotNull
            UUID leaseToken,

            @PositiveOrZero
            Integer chunkCount) {}

    public record FailInput(
            @NotNull
            @Min(1)
            @Max(2)
            Integer sourceType,

            @NotNull
            @Positive
            Long sourceId,

            @NotNull
            @Positive
            Long changeSeq,

            @NotNull
            UUID leaseToken,

            @NotNull
            @Min(1)
            @Max(4)
            Integer errorKind,

            boolean permanent) {}

    public record AuthItem(
            @NotNull
            @Min(1)
            @Max(2)
            Integer sourceType,

            @NotNull
            @Positive
            Long sourceId,

            @NotNull
            @Positive
            Long changeSeq) {}

    public record AuthInput(
            @NotNull
            @Size(max = 50)
            List<@NotNull @Valid AuthItem> items) {}

    public record RetryInput(
            @NotNull
            @Min(1)
            @Max(2)
            Integer sourceType,

            @NotNull
            @Positive
            Long sourceId) {}

    public record BackfillInput(
            @Size(min = 1, max = 2)
            List<@NotNull @Min(1) @Max(2) Integer> sourceTypes,

            boolean dryRun) {}

    public record ReconcileInput(
            @Size(min = 1, max = 2)
            List<@NotNull @Min(1) @Max(2) Integer> sourceTypes,

            List<@NotNull @Positive Long> sourceIds) {}

    public record Renewal(
            Instant leaseUntil) {}

    public record Completion(
            String status,

            boolean alreadyApplied) {}

    public record Authorization(
            List<CachedDocumentAuthSnapshot.Decision> items) {}

    public record ErrorBody(
            String code,

            String message,

            boolean retryable) {}

    private final KnowledgeSyncService knowledgeSyncService;
    private final CachedDocumentAuthSnapshot auth;

    public KnowledgeController(KnowledgeSyncService knowledgeSyncService, CachedDocumentAuthSnapshot auth) {
        this.knowledgeSyncService = knowledgeSyncService;
        this.auth = auth;
    }

    @PostMapping("/sync/claims")
    public KnowledgeSyncService.Claims claim(@Valid @RequestBody ClaimInput input) {
        return knowledgeSyncService.claim(types(input.sourceTypes()), input.limit() == null ? 1 : input.limit());
    }

    @GetMapping("/sync/claims/{queueId}/projection")
    public KnowledgeSyncService.ProjectionResponse projection(@PathVariable @Positive long queueId,
            @RequestParam @Positive long changeSeq, @RequestParam UUID leaseToken) {
        return knowledgeSyncService.projection(queueId, changeSeq, leaseToken);
    }

    @PostMapping("/sync/claims/{queueId}/renew")
    public Renewal renew(@PathVariable @Positive long queueId, @Valid @RequestBody LeaseInput input) {
        return new Renewal(knowledgeSyncService.renew(queueId, input.lease()));
    }

    @PostMapping("/sync/claims/{queueId}/complete")
    public Completion complete(@PathVariable @Positive long queueId, @Valid @RequestBody CompleteInput input) {
        knowledgeSyncService.complete(queueId, new KnowledgeSyncService.Lease(
                input.sourceType(), input.sourceId(), input.changeSeq(), input.leaseToken()));
        return new Completion("completed", false);
    }

    @PostMapping("/sync/claims/{queueId}/fail")
    public KnowledgeSyncService.Failure fail(@PathVariable @Positive long queueId, @Valid @RequestBody FailInput input) {
        return knowledgeSyncService.fail(queueId, new KnowledgeSyncService.Lease(
                input.sourceType(), input.sourceId(), input.changeSeq(), input.leaseToken()),
                input.errorKind(), input.permanent());
    }

    @PostMapping("/auth/validate")
    public Authorization validate(@Valid @RequestBody AuthInput input) {
        return new Authorization(auth.validate(input.items().stream()
                .map(item -> new CachedDocumentAuthSnapshot.Candidate(item.sourceType(), item.sourceId(), item.changeSeq()))
                .toList()));
    }

    @PostMapping("/sync/retry")
    public KnowledgeSyncService.Retry retry(@Valid @RequestBody RetryInput input) {
        return knowledgeSyncService.retry(input.sourceType(), input.sourceId());
    }

    @PostMapping("/sync/backfill")
    public KnowledgeSyncService.Backfill backfill(@Valid @RequestBody BackfillInput input) {
        return knowledgeSyncService.backfill(types(input.sourceTypes()), input.dryRun());
    }

    @PostMapping("/sync/reconcile")
    public KnowledgeSyncService.Reconcile reconcile(@Valid @RequestBody ReconcileInput input) {
        return knowledgeSyncService.reconcile(types(input.sourceTypes()), input.sourceIds());
    }

    @ExceptionHandler(KnowledgeEndpointException.class)
    ResponseEntity<ErrorBody> knowledgeError(KnowledgeEndpointException error) {
        return ResponseEntity.status(error.status()).body(new ErrorBody(error.code(), error.getMessage(), false));
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentNotValidException.class,
            HandlerMethodValidationException.class, MethodValidationException.class,
            ServletRequestBindingException.class, MethodArgumentTypeMismatchException.class})
    ResponseEntity<ErrorBody> invalidRequest() {
        return ResponseEntity.badRequest().body(new ErrorBody("INVALID_REQUEST", "Invalid knowledge request", false));
    }

    @ExceptionHandler(DataAccessException.class)
    ResponseEntity<ErrorBody> unavailable() {
        return ResponseEntity.status(503)
                .body(new ErrorBody("KNOWLEDGE_UNAVAILABLE", "Knowledge storage is temporarily unavailable", true));
    }

    private List<Integer> types(List<Integer> types) {
        return types == null ? List.of(1, 2) : types.stream()
                .distinct()
                .toList();
    }
}
