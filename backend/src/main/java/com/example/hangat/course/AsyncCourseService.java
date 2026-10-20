package com.example.hangat.course;

import com.example.hangat.course.model.CourseRequestDto;
import com.example.hangat.course.model.entity.Course;
import com.example.hangat.course.repository.AsyncCourseJobRepository;
import com.example.hangat.notification.service.inbox.NotificationService;
import com.example.hangat.user.model.User;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.persistence.EntityManager;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.concurrent.*;

/**
 * AI 생성 요청 접수 / DB 대기열 / 작업 실행 / 결과 조회.
 *
 * 요청 키는 중복 접수를 막는다.
 * leaseToken은 종료된 작업자가 뒤늦게 결과를 저장하는 것을 막는다.
 * 성공 상태와 코스, 알림은 같은 트랜잭션에서 저장한다.
 */
@Slf4j
@Service
@Profile("!batch")
@ConditionalOnProperty(
        name = "hangat.async.enabled",
        havingValue = "true"
)
public class AsyncCourseService {

    private final AsyncCourseJobRepository jobs;
    private final ObjectMapper mapper;
    private final CourseService courses;
    private final CourseAccommodationService accommodations;
    private final CourseClaimTokenService claimTokens;
    private final NotificationService notifications;
    private final EntityManager entityManager;
    private final TransactionTemplate transaction;

    @Value("${hangat.async.parallelism:2}")
    private int parallelism;

    @Value("${hangat.async.max-pending:50}")
    private int maxPending;

    @Value("${hangat.async.max-active-per-user:2}")
    private int maxActivePerUser;

    private ThreadPoolExecutor executor;

    private final Map<String, Ticket> active = new ConcurrentHashMap<>();
    private final Map<String, Thread> runningThreads = new ConcurrentHashMap<>();

    private record Ticket(
            String id,
            String leaseToken,
            Long userId,
            CourseRequestDto request
    ) {
    }

    public AsyncCourseService(
            AsyncCourseJobRepository jobs,
            ObjectMapper mapper,
            CourseService courses,
            CourseAccommodationService accommodations,
            CourseClaimTokenService claimTokens,
            NotificationService notifications,
            EntityManager entityManager,
            PlatformTransactionManager manager
    ) {
        this.jobs = jobs;
        this.mapper = mapper;
        this.courses = courses;
        this.accommodations = accommodations;
        this.claimTokens = claimTokens;
        this.notifications = notifications;
        this.entityManager = entityManager;
        this.transaction = new TransactionTemplate(manager);
    }

    @PostConstruct
    void initialize() {
        if (parallelism < 2 || parallelism > 3) {
            throw new IllegalStateException(
                    "AI 동시 실행 수는 2 또는 3으로 설정하세요."
            );
        }

        if (maxPending < parallelism || maxActivePerUser < 1) {
            throw new IllegalStateException("AI 대기열 설정을 확인하세요.");
        }

        executor = new ThreadPoolExecutor(
                parallelism,
                parallelism,
                0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(parallelism),
                runnable -> {
                    Thread thread = new Thread(runnable);
                    thread.setName("course-generation-" + thread.getId());
                    return thread;
                },
                new ThreadPoolExecutor.AbortPolicy()
        );
    }

    @PreDestroy
    void close() {
        if (executor != null) executor.shutdownNow();
    }

    // ────────────────────────── 요청 접수 ──────────────────────────

    public Map<String, Object> submit(
            Long userId,
            String requestKey,
            CourseRequestDto request
    ) {
        UUID.fromString(requestKey);
        validateRequest(request);

        String json = writeJson(request);
        String hash = sha256(json);

        return transaction.execute(status -> {
            jobs.lockQueue();

            requireLockedActiveUser(userId);

            List<Map<String, Object>> existing = jobs.findByUserAndRequestKey(userId, requestKey);

            if (!existing.isEmpty()) {
                Map<String, Object> row = existing.get(0);

                if (!hash.equals(row.get("request_hash"))) {
                    throw problem(
                            HttpStatus.CONFLICT,
                            "같은 요청 키에 다른 여행 조건을 사용할 수 없습니다."
                    );
                }

                return jobDto(row);
            }

            Long ownActive = jobs.countActiveByUser(userId);

            if (ownActive != null && ownActive >= maxActivePerUser) {
                throw problem(
                        HttpStatus.TOO_MANY_REQUESTS,
                        "이미 처리 중인 코스가 있습니다. 최근 요청을 확인하세요."
                );
            }

            Long pending = jobs.countActive();

            if (pending != null && pending >= maxPending) {
                throw problem(
                        HttpStatus.SERVICE_UNAVAILABLE,
                        "생성 요청이 많습니다. 잠시 후 다시 시도하세요."
                );
            }

            String id = UUID.randomUUID().toString();

            jobs.insertQueued(
                    id, userId, requestKey, hash, json,
                    request.getStartDate(), request.getEndDate()
            );

            return jobDto(findOwned(id, userId));
        });
    }

    // ────────────────────────── 작업 실행 ──────────────────────────

    @Scheduled(fixedDelay = 1000, scheduler = "alarmScheduler")
    public void dispatch() {
        recoverExpired();

        while (active.size() < parallelism) {
            Ticket ticket = claim();
            if (ticket == null) {
                return;
            }

            active.put(ticket.id(), ticket);

            try {
                executor.execute(() -> execute(ticket));
            } catch (RejectedExecutionException rejected) {
                active.remove(ticket.id(), ticket);

                transaction.executeWithoutResult(status -> {
                    if (jobs.lockActiveUser(ticket.userId())) jobs.requeueIfOwned(ticket.id(), ticket.leaseToken());
                });

                return;
            }
        }
    }

    private Ticket claim() {
        return transaction.execute(status -> {
            jobs.lockQueue();

            Long running = jobs.countRunning();

            if (running != null && running >= parallelism) {
                return null;
            }

            List<Map<String, Object>> rows = jobs.findOldestQueued();

            if (rows.isEmpty()) {
                return null;
            }

            Map<String, Object> row = rows.get(0);
            String id = (String) row.get("id");
            Long userId = ((Number) row.get("user_id")).longValue();
            if (!jobs.lockActiveUser(userId)) return null;
            if (!"QUEUED".equals(jobs.lockJob(id).get("status"))) return null;
            String leaseToken = UUID.randomUUID().toString();

            jobs.markRunning(id, leaseToken);

            return new Ticket(
                    id,
                    leaseToken,
                    userId,
                    readRequest((String) row.get("request_json"))
            );
        });
    }

    private void execute(Ticket ticket) {
        runningThreads.put(ticket.id(), Thread.currentThread());
        try (var diagnosticContext = com.example.hangat.course.ai.CourseAiDiagnosticContext
                .forJob(UUID.fromString(ticket.id()))) {
            requireActiveUser(ticket.userId());

            // 외부 API와 Gemini 호출 중에는 작업 행의 DB 잠금을 잡지 않는다.
            CourseService.ComputedCourse computed =
                    courses.computeCourse(ticket.request());

            if (Thread.currentThread().isInterrupted()) throw new CancellationException();
            var accommodation = accommodations.verifyGeneratedAccommodation(ticket.request().getAccommodation(), computed);
            if (Thread.currentThread().isInterrupted()) throw new CancellationException();
            finish(ticket, computed, accommodation);
        } catch (RuntimeException failure) {
            log.warn(
                    "AI_JOB_FAILED jobId={} exception={}",
                    ticket.id(),
                    failure.getClass().getSimpleName()
            );

            // Gemini 내부 재시도가 모두 끝난 뒤의 최종 실패만 기록한다.
            fail(
                    ticket.id(),
                    ticket.leaseToken(),
                    "AI_GENERATION_FAILED"
            );
        } finally {
            runningThreads.remove(ticket.id(), Thread.currentThread());
            active.remove(ticket.id(), ticket);
        }
    }

    private void finish(
            Ticket ticket,
            CourseService.ComputedCourse computed,
            KakaoAccommodationProvider.VerifiedAccommodation accommodation
    ) {
        transaction.executeWithoutResult(status -> {
            requireLockedActiveUser(ticket.userId());
            Map<String, Object> row = jobs.lockJob(ticket.id());

            if (!ownsLease(row, ticket.leaseToken())) {
                return;
            }

            var response = courses.persistComputedCourse(
                    ticket.request(),
                    computed
            );

            Course course = entityManager.find(
                    Course.class,
                    response.id()
            );

            course.assignGenerationOwner(
                    entityManager.getReference(User.class, ticket.userId())
            );
            accommodations.attachGeneratedAccommodation(course, accommodation);

            entityManager.flush();

            int updated = jobs.markSucceededIfLeaseValid(
                    ticket.id(),
                    ticket.leaseToken(),
                    response.id()
            );

            if (updated != 1) {
                // 코스 INSERT도 같은 트랜잭션이므로 함께 롤백한다.
                throw new IllegalStateException("작업 실행 권한이 만료됐습니다.");
            }

            notifications.enqueue(
                    ticket.userId(),
                    "AI_COURSE_COMPLETED",
                    "AI 코스가 완성됐어요",
                    "생성된 코스를 확인하고 마음에 들면 저장해 주세요.",
                    "COURSE_GENERATION",
                    ticket.id(),
                    "AI_SUCCESS:" + ticket.id()
            );
        });
    }

    // ────────────────────────── 실행 유지 / 중단 복구 ──────────────────────────

    @Scheduled(fixedDelay = 5000, scheduler = "alarmScheduler")
    public void heartbeat() {
        for (Ticket ticket : active.values()) {
            int updated = transaction.execute(status -> jobs.lockActiveUser(ticket.userId())
                    ? jobs.extendLeaseIfValid(ticket.id(), ticket.leaseToken()) : 0);
            // 만료된 실행은 중단을 요청한다. 실제 종료 전에는 슬롯을 빼지 않아 동시 실행 상한을 지킨다.
            if (updated == 0) {
                // finally의 제거와 원자적으로 처리하여 풀에서 재사용된 다음 작업을 중단하지 않는다.
                runningThreads.computeIfPresent(ticket.id(), (id, worker) -> {
                    worker.interrupt();
                    return worker;
                });
            }
        }
    }

    private void recoverExpired() {
        List<Map<String, Object>> rows = jobs.findExpiredRunning();

        for (Map<String, Object> row : rows) {
            fail(
                    (String) row.get("id"),
                    (String) row.get("lease_token"),
                    "WORKER_INTERRUPTED"
            );
        }
    }

    private void fail(String id, String leaseToken, String errorCode) {
        transaction.executeWithoutResult(status -> {
            Long userId = jobs.findOwner(id);
            if (userId == null || !jobs.lockActiveUser(userId)) return;
            Map<String, Object> row = jobs.lockJob(id);

            if (!"RUNNING".equals(row.get("status"))
                    || !Objects.equals(
                    leaseToken,
                    row.get("lease_token")
            )) {
                return;
            }

            // 만료 후보를 읽은 뒤 heartbeat가 갱신됐을 수 있으므로 행 잠금 후 다시 검사한다.
            if ("WORKER_INTERRUPTED".equals(errorCode) && ownsLease(row, leaseToken)) return;

            jobs.markFailed(id, errorCode);

            Long activeUser = jobs.countActiveUser(userId);

            if (activeUser == null || activeUser == 0) {
                return;
            }

            notifications.enqueue(
                    userId,
                    "AI_COURSE_FAILED",
                    "AI 코스 생성에 실패했어요",
                    "요청을 완료하지 못했어요. 여행 조건을 확인한 뒤 다시 요청해 주세요.",
                    "COURSE_GENERATION",
                    id,
                    "AI_FAILURE:" + id
            );
        });
    }

    // ────────────────────────── 본인 작업 조회 ──────────────────────────

    public Map<String, Object> status(String id, Long userId) {
        return jobDto(findOwned(id, userId));
    }

    public Map<String, Object> recent(Long userId) {
        return recent(userId, 0, 20);
    }

    public Map<String, Object> recent(Long userId, int page, int size) {
        if (userId == null) throw problem(HttpStatus.UNAUTHORIZED, "로그인이 필요합니다.");
        if (page < 0 || page > 10000 || size < 1 || size > 20) {
            throw problem(HttpStatus.BAD_REQUEST, "페이지는 0~10000, 크기는 1~20이어야 합니다.");
        }
        var rows = jobs.findPageByUser(userId, page, size);
        return Map.of("items", rows.stream().limit(size).map(this::jobDto).toList(),
                "page", page, "size", size, "hasNext", rows.size() > size);
    }

    /**
     * 알림을 늦게 열어도 본인에게 새로운 저장 증명을 발급한다.
     * 프론트는 이 ID로 기존 상세 API를 조회해 현재 일정을 복원한다.
     */
    public Map<String, Object> result(String id, Long userId) {
        return transaction.execute(status -> {
            Map<String, Object> job = findOwned(id, userId);

            if (!"SUCCEEDED".equals(job.get("status"))) {
                throw problem(
                        HttpStatus.CONFLICT,
                        "아직 완료된 작업이 아닙니다."
                );
            }

            Long courseId =
                    ((Number) job.get("course_id")).longValue();

            List<Map<String, Object>> courses = jobs.lockOwnedResultCourse(courseId, userId);

            if (courses.isEmpty()) {
                throw problem(
                        HttpStatus.NOT_FOUND,
                        "코스를 찾을 수 없습니다."
                );
            }

            Map<String, Object> course = new LinkedHashMap<>();
            course.put("id", courseId);

            if ("READY".equals(courses.get(0).get("status"))) {
                var proof = claimTokens.issue(courseId);
                course.put("claim_token", proof.token());
                course.put("claim_expires_at", proof.expiresAt());
            }

            return Map.of(
                    "course", course,
                    "request", readRequest(
                            (String) job.get("request_json")
                    )
            );
        });
    }

    // ────────────────────────── 내부 검증 / 변환 ──────────────────────────

    private boolean ownsLease(
            Map<String, Object> row,
            String leaseToken
    ) {
        Object valid = row.get("lease_valid");
        boolean leaseValid = valid instanceof Boolean booleanValue
                ? booleanValue
                : valid instanceof Number number
                && number.intValue() == 1;

        return "RUNNING".equals(row.get("status"))
                && Objects.equals(leaseToken, row.get("lease_token"))
                && leaseValid;
    }

    private Map<String, Object> findOwned(String id, Long userId) {
        List<Map<String, Object>> rows = jobs.findOwned(id, userId);

        if (rows.isEmpty()) {
            throw problem(
                    HttpStatus.NOT_FOUND,
                    "작업이 없거나 조회할 권한이 없습니다."
            );
        }

        return rows.get(0);
    }

    private Map<String, Object> jobDto(Map<String, Object> row) {
        Map<String, Object> dto = new LinkedHashMap<>();
        dto.put("jobId", row.get("id"));
        dto.put("status", row.get("status"));
        dto.put("courseId", row.get("course_id"));
        dto.put("errorCode", row.get("error_code"));
        Object createdAt = row.get("created_at");
        // DATETIME follows the existing UTC storage contract.
        dto.put("createdAt", createdAt instanceof java.sql.Timestamp timestamp
                ? timestamp.toLocalDateTime().atOffset(java.time.ZoneOffset.UTC).toString()
                : createdAt instanceof java.time.LocalDateTime dateTime
                ? dateTime.atOffset(java.time.ZoneOffset.UTC).toString() : null);
        dto.put("startDate", row.get("start_date").toString());
        dto.put("endDate", row.get("end_date").toString());
        return dto;
    }

    private void requireActiveUser(Long userId) {
        Long count = jobs.countActiveUser(userId);

        if (count == null || count == 0) {
            throw problem(
                    HttpStatus.FORBIDDEN,
                    "사용할 수 없는 계정입니다."
            );
        }
    }

    private void requireLockedActiveUser(Long userId) {
        if (!jobs.lockActiveUser(userId)) throw problem(HttpStatus.FORBIDDEN, "사용할 수 없는 계정입니다.");
    }

    private void validateRequest(CourseRequestDto request) {
        if (request == null
                || request.getStartDate() == null
                || request.getEndDate() == null
                || request.getEndDate().isBefore(request.getStartDate())
                || request.getPeople() == null
                || request.getPeople() < 1
                || request.getPeople() > 100) {
            throw problem(
                    HttpStatus.BAD_REQUEST,
                    "여행 날짜와 인원을 확인하세요."
            );
        }
    }

    private String writeJson(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(
                    "요청을 직렬화하지 못했습니다.",
                    exception
            );
        }
    }

    private CourseRequestDto readRequest(String json) {
        try {
            return mapper.readValue(json, CourseRequestDto.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(
                    "저장된 요청을 읽지 못했습니다.",
                    exception
            );
        }
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256")
                            .digest(value.getBytes(StandardCharsets.UTF_8))
            );
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private ResponseStatusException problem(
            HttpStatus status,
            String message
    ) {
        return new ResponseStatusException(status, message);
    }
}
