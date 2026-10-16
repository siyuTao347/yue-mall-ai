package com.example.item.reconciliation.controller;

import api.context.UserContext;
import api.trade.MerchantDubboService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.item.payment.domain.ChannelCashReceipt;
import com.example.item.payment.domain.ChannelSettlementOrder;
import com.example.item.payment.domain.PaymentMethod;
import com.example.item.payment.mapper.ChannelCashReceiptMapper;
import com.example.item.payment.mapper.ChannelSettlementOrderMapper;
import com.example.item.payment.mock.MockDailyStatementService;
import com.example.item.payment.mock.MockFaultPlan;
import com.example.item.payment.mock.MockFaultPlanMapper;
import com.example.item.payment.mock.MockStatement;
import com.example.item.payment.mock.MockStatementMapper;
import com.example.item.reconciliation.application.ReconciliationExecutionService;
import com.example.item.reconciliation.application.ReconciliationRepairService;
import com.example.item.reconciliation.domain.*;
import com.example.item.reconciliation.mapper.*;
import com.example.item.reconciliation.statement.ReconciliationStatementParser;
import lombok.extern.slf4j.Slf4j;
import org.apache.dubbo.config.annotation.DubboReference;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

@RestController
@Slf4j
@RequestMapping("/api/trade/admin")
public class AdminReconciliationController {

    private final ReconTaskMapper taskMapper;
    private final ReconRunMapper runMapper;
    private final ReconDiffMapper diffMapper;
    private final ReconFileMapper fileMapper;
    private final ReconMatchMapper matchMapper;
    private final ChannelSettlementOrderMapper settlementOrderMapper;
    private final ChannelCashReceiptMapper cashReceiptMapper;
    private final MockFaultPlanMapper faultPlanMapper;
    private final MockStatementMapper mockStatementMapper;

    private final MockDailyStatementService statementService;
    private final ReconciliationStatementParser statementParser;
    private final ReconciliationExecutionService executionService;
    private final ReconciliationRepairService repairService;

    @DubboReference(timeout = 5000, retries = 0, check = false)
    private MerchantDubboService merchantService;

    public AdminReconciliationController(
            ReconTaskMapper taskMapper,
            ReconRunMapper runMapper,
            ReconDiffMapper diffMapper,
            ReconFileMapper fileMapper,
            ReconMatchMapper matchMapper,
            ChannelSettlementOrderMapper settlementOrderMapper,
            ChannelCashReceiptMapper cashReceiptMapper,
            MockFaultPlanMapper faultPlanMapper,
            MockStatementMapper mockStatementMapper,
            MockDailyStatementService statementService,
            ReconciliationStatementParser statementParser,
            ReconciliationExecutionService executionService,
            ReconciliationRepairService repairService
    ) {
        this.taskMapper = taskMapper;
        this.runMapper = runMapper;
        this.diffMapper = diffMapper;
        this.fileMapper = fileMapper;
        this.matchMapper = matchMapper;
        this.settlementOrderMapper = settlementOrderMapper;
        this.cashReceiptMapper = cashReceiptMapper;
        this.faultPlanMapper = faultPlanMapper;
        this.mockStatementMapper = mockStatementMapper;
        this.statementService = statementService;
        this.statementParser = statementParser;
        this.executionService = executionService;
        this.repairService = repairService;
    }

    private void checkAdmin() {
        Long userId = UserContext.getUserId();
        if (userId == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "请先登录");
        }
        if (merchantService != null) {
            try {
                if (!merchantService.isAdmin(userId)) {
                    throw new ResponseStatusException(HttpStatus.FORBIDDEN, "无管理员权限");
                }
            } catch (ResponseStatusException rse) {
                throw rse;
            } catch (Exception e) {
                log.warn("Failed to check admin status via dubbo, fallback to check id: {}", e.getMessage());
                if (userId != 1L) {
                    throw new ResponseStatusException(HttpStatus.FORBIDDEN, "无管理员权限");
                }
            }
        }
    }

    @GetMapping("/reconciliation/runs")
    public ResponseEntity<Map<String, Object>> listRuns(
            @RequestParam(required = false) String channel,
            @RequestParam(required = false) String businessDate,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        checkAdmin();
        LambdaQueryWrapper<ReconRun> query = new LambdaQueryWrapper<>();
        if (status != null && !status.isBlank()) {
            query.eq(ReconRun::getExecutionStatus, status);
        }
        query.orderByDesc(ReconRun::getId);

        List<ReconRun> all = runMapper.selectList(query);
        int total = all.size();
        int fromIndex = Math.min((page - 1) * size, total);
        int toIndex = Math.min(fromIndex + size, total);
        List<ReconRun> pageList = all.subList(fromIndex, toIndex);

        return ResponseEntity.ok(Map.of(
                "code", 200,
                "data", Map.of(
                        "total", total,
                        "page", page,
                        "size", size,
                        "list", pageList
                )
        ));
    }

    @PostMapping("/reconciliation/runs")
    public ResponseEntity<Map<String, Object>> triggerRun(@RequestBody(required = false) Map<String, Object> body) {
        checkAdmin();
        String channel = body != null ? (String) body.get("channel") : null;
        String dateStr = body != null ? (String) body.get("businessDate") : null;
        LocalDate date = (dateStr != null && !dateStr.isBlank())
                ? LocalDate.parse(dateStr)
                : LocalDate.now().minusDays(1);

        List<PaymentMethod> methodsToRun = new ArrayList<>();
        if (channel == null || channel.isBlank() || "ALL".equalsIgnoreCase(channel)) {
            methodsToRun.addAll(Arrays.asList(PaymentMethod.values()));
        } else {
            methodsToRun.add(PaymentMethod.valueOf(channel));
        }

        List<Map<String, Object>> executedRuns = new ArrayList<>();
        for (PaymentMethod method : methodsToRun) {
            try {
                ReconRun run = executionService.executeReconciliation(method, "TRADE", date, "ADMIN_TRIGGER");
                executedRuns.add(Map.of(
                        "channel", method.name(),
                        "runNo", run.getRunNo(),
                        "status", run.getExecutionStatus() != null ? run.getExecutionStatus() : "COMPLETED",
                        "diffCount", run.getDiffCount() != null ? run.getDiffCount() : 0,
                        "matchedCount", run.getMatchedCount() != null ? run.getMatchedCount() : 0,
                        "localCount", run.getLocalCount() != null ? run.getLocalCount() : 0,
                        "channelCount", run.getChannelCount() != null ? run.getChannelCount() : 0
                ));
            } catch (Exception e) {
                log.error("Failed to run reconciliation for {}", method, e);
                executedRuns.add(Map.of(
                        "channel", method.name(),
                        "error", e.getMessage()
                ));
            }
        }

        return ResponseEntity.ok(Map.of(
                "code", 200,
                "message", "对账任务已执行完成",
                "data", executedRuns
        ));
    }

    @GetMapping("/reconciliation/runs/{runNo}")
    public ResponseEntity<Map<String, Object>> getRunDetail(@PathVariable String runNo) {
        checkAdmin();
        ReconRun run = runMapper.selectByRunNo(runNo);
        if (run == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("code", 404, "message", "对账批次不存在"));
        }
        List<ReconDiff> diffs = diffMapper.selectList(new LambdaQueryWrapper<ReconDiff>()
                .eq(ReconDiff::getLastSeenRunNo, runNo).or().eq(ReconDiff::getFirstSeenRunNo, runNo));
        List<ReconMatch> matches = matchMapper.selectList(new LambdaQueryWrapper<ReconMatch>()
                .eq(ReconMatch::getRunNo, runNo));

        return ResponseEntity.ok(Map.of(
                "code", 200,
                "data", Map.of(
                        "run", run,
                        "diffs", diffs,
                        "matches", matches
                )
        ));
    }

    @GetMapping("/reconciliation/diffs")
    public ResponseEntity<Map<String, Object>> listDiffs(
            @RequestParam(required = false) String runNo,
            @RequestParam(required = false) String channel,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String diffType,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        checkAdmin();
        LambdaQueryWrapper<ReconDiff> query = new LambdaQueryWrapper<>();
        if (runNo != null && !runNo.isBlank()) {
            query.and(q -> q.eq(ReconDiff::getLastSeenRunNo, runNo).or().eq(ReconDiff::getFirstSeenRunNo, runNo));
        }
        if (channel != null && !channel.isBlank()) {
            query.like(ReconDiff::getProfileId, channel);
        }
        if (status != null && !status.isBlank()) {
            query.eq(ReconDiff::getStatus, status);
        }
        if (diffType != null && !diffType.isBlank()) {
            query.eq(ReconDiff::getDiffType, diffType);
        }
        query.orderByDesc(ReconDiff::getId);

        List<ReconDiff> all = diffMapper.selectList(query);
        int total = all.size();
        int fromIndex = Math.min((page - 1) * size, total);
        int toIndex = Math.min(fromIndex + size, total);
        List<ReconDiff> pageList = all.subList(fromIndex, toIndex);

        return ResponseEntity.ok(Map.of(
                "code", 200,
                "data", Map.of(
                        "total", total,
                        "page", page,
                        "size", size,
                        "list", pageList
                )
        ));
    }

    @PostMapping("/reconciliation/diffs/{diffNo}/actions")
    public ResponseEntity<Map<String, Object>> handleDiffAction(
            @PathVariable String diffNo,
            @RequestBody Map<String, String> body
    ) {
        checkAdmin();
        String action = body.get("action");
        String reason = body.get("reason");
        String requestKey = body.get("requestKey");
        if (requestKey == null || requestKey.isBlank()) {
            requestKey = UUID.randomUUID().toString();
        }
        Long adminId = UserContext.getUserId();
        String operator = "ADMIN_" + (adminId != null ? adminId : "SYSTEM");

        try {
            Map<String, Object> result = repairService.handleDiffAction(diffNo, action, reason, operator, requestKey);
            return ResponseEntity.ok(Map.of(
                    "code", 200,
                    "message", "差异操作成功",
                    "data", result
            ));
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("code", 400, "message", e.getMessage()));
        } catch (Exception e) {
            log.error("Diff action failed for {}", diffNo, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("code", 500, "message", "差异操作异常: " + e.getMessage()));
        }
    }

    @GetMapping("/reconciliation/files/{fileId}")
    public ResponseEntity<Map<String, Object>> getFile(@PathVariable Long fileId) {
        checkAdmin();
        ReconFile file = fileMapper.selectById(fileId);
        if (file == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("code", 404, "message", "文件不存在"));
        }
        MockStatement statement = mockStatementMapper.selectById(fileId);
        return ResponseEntity.ok(Map.of(
                "code", 200,
                "data", Map.of(
                        "file", file,
                        "content", statement != null ? statement.getFileContent() : file.getRawContent()
                )
        ));
    }

    @GetMapping("/channel-settlements")
    public ResponseEntity<Map<String, Object>> listSettlements(
            @RequestParam(required = false) String channel,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        checkAdmin();
        LambdaQueryWrapper<ChannelSettlementOrder> query = new LambdaQueryWrapper<>();
        if (channel != null && !channel.isBlank()) {
            query.like(ChannelSettlementOrder::getProfileId, channel);
        }
        if (status != null && !status.isBlank()) {
            query.eq(ChannelSettlementOrder::getStatus, status);
        }
        query.orderByDesc(ChannelSettlementOrder::getId);

        List<ChannelSettlementOrder> all = settlementOrderMapper.selectList(query);
        int total = all.size();
        int fromIndex = Math.min((page - 1) * size, total);
        int toIndex = Math.min(fromIndex + size, total);
        List<ChannelSettlementOrder> pageList = all.subList(fromIndex, toIndex);

        List<ChannelCashReceipt> receipts = cashReceiptMapper.selectList(
                new LambdaQueryWrapper<ChannelCashReceipt>().orderByDesc(ChannelCashReceipt::getId).last("LIMIT 50"));

        return ResponseEntity.ok(Map.of(
                "code", 200,
                "data", Map.of(
                        "total", total,
                        "page", page,
                        "size", size,
                        "list", pageList,
                        "recentReceipts", receipts
                )
        ));
    }

    @PostMapping("/mock/fault-plans")
    public ResponseEntity<Map<String, Object>> createFaultPlan(@RequestBody Map<String, Object> body) {
        checkAdmin();
        String planName = (String) body.get("planName");
        String faultType = (String) body.get("faultType");
        String targetAttemptNo = (String) body.get("targetAttemptNo");
        String paramsJson = (String) body.get("paramsJson");
        Boolean enabled = (Boolean) body.getOrDefault("enabled", true);

        MockFaultPlan plan = MockFaultPlan.builder()
                .scopeId("DEFAULT")
                .scenarioKey(planName != null ? planName : faultType)
                .planVersion(1)
                .targetAttemptNo(targetAttemptNo)
                .paramsJson(paramsJson != null ? paramsJson : "{}")
                .enabled(Boolean.TRUE.equals(enabled) ? 1 : 0)
                .createdTime(LocalDateTime.now())
                .updatedTime(LocalDateTime.now())
                .build();
        faultPlanMapper.insert(plan);

        return ResponseEntity.ok(Map.of(
                "code", 200,
                "message", "故障注入预案创建成功",
                "data", plan
        ));
    }

    @GetMapping("/mock/fault-plans")
    public ResponseEntity<Map<String, Object>> listFaultPlans() {
        checkAdmin();
        List<MockFaultPlan> list = faultPlanMapper.selectList(
                new LambdaQueryWrapper<MockFaultPlan>().orderByDesc(MockFaultPlan::getId));
        return ResponseEntity.ok(Map.of(
                "code", 200,
                "data", list
        ));
    }

    public static class ResponseStatusException extends RuntimeException {
        private final HttpStatus status;
        public ResponseStatusException(HttpStatus status, String message) {
            super(message);
            this.status = status;
        }
        public HttpStatus getStatus() { return status; }
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, Object>> handleResponseStatus(ResponseStatusException e) {
        return ResponseEntity.status(e.getStatus()).body(Map.of(
                "code", e.getStatus().value(),
                "message", e.getMessage()
        ));
    }
}
