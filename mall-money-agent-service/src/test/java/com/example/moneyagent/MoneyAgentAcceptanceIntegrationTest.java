package com.example.moneyagent;

import api.agent.money.*;
import api.agent.money.contract.MoneyAdditionalInput;
import api.agent.money.contract.MoneyAgentException;
import api.agent.money.contract.MoneyInvestigationRequest;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.moneyagent.application.MoneyCaseService;
import com.example.moneyagent.application.MoneyGrantService;
import com.example.moneyagent.application.MoneyInputService;
import com.example.moneyagent.investigation.*;
import com.example.moneyagent.metrics.MoneyOperationalMetrics;
import com.example.moneyagent.persistence.entity.*;
import com.example.moneyagent.persistence.mapper.*;
import com.example.moneyagent.tools.OrderMoneyQueryClient;
import com.example.moneyagent.tools.UserFundQueryClient;
import com.example.moneyagent.worker.MoneyTaskWorker;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.stubbing.Answer;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 资金调查 Agent 全链路端到端验收集成测试
 * 覆盖：
 * 1. active_dedupe_key 并发去重锁与行锁生效测试
 * 2. 断网重试后幂等请求重放测试（相同 requestId 返回真实受理回执，caseNo/taskNo/contextId 一致）
 * 3. 补证竞争测试（submitInput 版本冲突与重复补证防重）
 * 4. 受众投影隔离测试（BUYER 投影遮蔽敏感明细，FINANCE 投影完整呈现证据链）
 * 5. 全链路链路追踪传播、运营指标统计与快照回放服务测试
 */
class MoneyAgentAcceptanceIntegrationTest {

    private final ConcurrentHashMap<String, MoneyCaseEntity> caseDb = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, MoneyTaskEntity> taskDb = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, MoneyReportEntity> reportDb = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, MoneyRequestEntity> requestDb = new ConcurrentHashMap<>();
    private final List<MoneyProjectionEntity> projectionDb = new CopyOnWriteArrayList<>();
    private final List<MoneyEventEntity> eventDb = new CopyOnWriteArrayList<>();
    private final List<MoneySnapshotEntity> snapshotDb = new CopyOnWriteArrayList<>();
    private final List<MoneyToolCallEntity> toolCallDb = new CopyOnWriteArrayList<>();
    private final ConcurrentHashMap<String, MoneyGrantEntity> grantDb = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, MoneyTaskAccessEntity> taskAccessDb = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, MoneyInputRequestEntity> inputRequestDb = new ConcurrentHashMap<>();
    private final List<MoneyTaskInputEntity> taskInputDb = new CopyOnWriteArrayList<>();

    private MoneyCaseMapper caseMapper;
    private MoneyTaskMapper taskMapper;
    private MoneyReportMapper reportMapper;
    private MoneyRequestMapper requestMapper;
    private MoneyProjectionMapper projectionMapper;
    private MoneyEventMapper eventMapper;
    private MoneySnapshotMapper snapshotMapper;
    private MoneyToolCallMapper toolCallMapper;
    private MoneyOutboxMapper outboxMapper;
    private MoneyGrantMapper grantMapper;
    private MoneyTaskAccessMapper taskAccessMapper;
    private MoneyInputRequestMapper inputRequestMapper;
    private MoneyTaskInputMapper taskInputMapper;

    private OrderMoneyQueryClient orderQueryClient;
    private UserFundQueryClient fundQueryClient;
    private ObjectMapper objectMapper;

    private MoneyGrantService grantService;
    private MoneyOperationalMetrics metrics;
    private MoneyInvestigationGraph investigationGraph;
    private MoneyCaseService caseService;
    private MoneyInputService inputService;
    private MoneyTaskWorker taskWorker;
    private MoneyReplayService replayService;

    @BeforeEach
    void setUp() {
        caseDb.clear();
        taskDb.clear();
        reportDb.clear();
        requestDb.clear();
        projectionDb.clear();
        eventDb.clear();
        snapshotDb.clear();
        toolCallDb.clear();
        grantDb.clear();
        taskAccessDb.clear();
        inputRequestDb.clear();
        taskInputDb.clear();

        objectMapper = new ObjectMapper();
        metrics = new MoneyOperationalMetrics();

        // 初始化 MyBatis-Plus 实体表元数据与 Lambda 字段缓存
        org.apache.ibatis.builder.MapperBuilderAssistant assistant =
                new org.apache.ibatis.builder.MapperBuilderAssistant(new com.baomidou.mybatisplus.core.MybatisConfiguration(), "");
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(assistant, MoneyCaseEntity.class);
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(assistant, MoneyTaskEntity.class);
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(assistant, MoneyRequestEntity.class);
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(assistant, MoneyProjectionEntity.class);
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(assistant, MoneyInputRequestEntity.class);
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(assistant, MoneyReportEntity.class);
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(assistant, MoneyGrantEntity.class);

        // 构造有状态的并发安全持久层 Mock
        caseMapper = mock(MoneyCaseMapper.class);
        when(caseMapper.selectActiveByDedupeKeyForUpdate(any())).thenAnswer((Answer<MoneyCaseEntity>) invocation -> {
            String dedupeKey = invocation.getArgument(0);
            if (dedupeKey == null) return null;
            for (MoneyCaseEntity c : caseDb.values()) {
                if (dedupeKey.equals(c.getActiveDedupeKey()) && List.of("OPEN", "NEED_INPUT", "NEED_REVIEW", "WAITING_BIZ").contains(c.getState())) {
                    return c;
                }
            }
            return null;
        });
        when(caseMapper.insert(any(MoneyCaseEntity.class))).thenAnswer((Answer<Integer>) invocation -> {
            MoneyCaseEntity entity = invocation.getArgument(0);
            if (entity.getId() == null) entity.setId((long) (caseDb.size() + 1));
            // 校验 activeDedupeKey 唯一约束
            if (entity.getActiveDedupeKey() != null) {
                synchronized (caseDb) {
                    for (MoneyCaseEntity existing : caseDb.values()) {
                        if (entity.getActiveDedupeKey().equals(existing.getActiveDedupeKey())
                                && !Objects.equals(entity.getCaseNo(), existing.getCaseNo())) {
                            throw new RuntimeException("Duplicate entry '" + entity.getActiveDedupeKey() + "' for key 'uk_active_dedupe_key'");
                        }
                    }
                    caseDb.put(entity.getCaseNo(), entity);
                }
            } else {
                caseDb.put(entity.getCaseNo(), entity);
            }
            return 1;
        });
        when(caseMapper.updateById(any(MoneyCaseEntity.class))).thenAnswer((Answer<Integer>) invocation -> {
            MoneyCaseEntity entity = invocation.getArgument(0);
            caseDb.put(entity.getCaseNo(), entity);
            return 1;
        });
        when(caseMapper.selectOne(any())).thenAnswer((Answer<MoneyCaseEntity>) invocation -> {
            LambdaQueryWrapper<MoneyCaseEntity> wrapper = invocation.getArgument(0);
            String sqlSegment = wrapper.getSqlSegment();
            // 简单根据 caseDb 匹配
            for (MoneyCaseEntity c : caseDb.values()) {
                if (sqlSegment != null && sqlSegment.contains("active_dedupe_key") && c.getActiveDedupeKey() != null) {
                    return c;
                }
                if (sqlSegment != null && sqlSegment.contains("case_no")) {
                    return c;
                }
            }
            return caseDb.values().stream().findFirst().orElse(null);
        });

        taskMapper = mock(MoneyTaskMapper.class);
        when(taskMapper.insert(any(MoneyTaskEntity.class))).thenAnswer((Answer<Integer>) invocation -> {
            MoneyTaskEntity entity = invocation.getArgument(0);
            if (entity.getId() == null) entity.setId((long) (taskDb.size() + 1));
            taskDb.put(entity.getTaskNo(), entity);
            return 1;
        });
        when(taskMapper.updateById(any(MoneyTaskEntity.class))).thenAnswer((Answer<Integer>) invocation -> {
            MoneyTaskEntity entity = invocation.getArgument(0);
            taskDb.put(entity.getTaskNo(), entity);
            return 1;
        });
        when(taskMapper.selectRunnableTaskForUpdate(any())).thenAnswer((Answer<MoneyTaskEntity>) invocation -> {
            for (MoneyTaskEntity t : taskDb.values()) {
                if (List.of("SUBMITTED", "WORKING").contains(t.getState()) && t.getLeaseOwner() == null) {
                    return t;
                }
            }
            return null;
        });
        when(taskMapper.selectOne(any())).thenAnswer((Answer<MoneyTaskEntity>) invocation -> {
            LambdaQueryWrapper<MoneyTaskEntity> wrapper = invocation.getArgument(0);
            if (wrapper != null) {
                try {
                    wrapper.getCustomSqlSegment();
                    Map<String, Object> params = wrapper.getParamNameValuePairs();
                    if (params != null) {
                        for (MoneyTaskEntity t : taskDb.values()) {
                            if (params.containsValue(t.getTaskNo())) {
                                return t;
                            }
                        }
                    }
                } catch (Exception ignored) {}
            }
            return taskDb.values().stream().findFirst().orElse(null);
        });

        reportMapper = mock(MoneyReportMapper.class);
        when(reportMapper.insert(any(MoneyReportEntity.class))).thenAnswer((Answer<Integer>) invocation -> {
            MoneyReportEntity entity = invocation.getArgument(0);
            reportDb.put(entity.getReportNo(), entity);
            return 1;
        });
        when(reportMapper.selectOne(any())).thenAnswer((Answer<MoneyReportEntity>) invocation -> {
            return reportDb.values().stream().findFirst().orElse(null);
        });

        requestMapper = mock(MoneyRequestMapper.class);
        when(requestMapper.insert(any(MoneyRequestEntity.class))).thenAnswer((Answer<Integer>) invocation -> {
            MoneyRequestEntity req = invocation.getArgument(0);
            String key = req.getCallerId() + ":" + req.getAction() + ":" + req.getRequestId();
            if (requestDb.containsKey(key)) {
                throw new RuntimeException("Duplicate entry for key 'uk_caller_action_request'");
            }
            requestDb.put(key, req);
            return 1;
        });
        when(requestMapper.selectOne(any())).thenAnswer((Answer<MoneyRequestEntity>) invocation -> {
            LambdaQueryWrapper<MoneyRequestEntity> wrapper = invocation.getArgument(0);
            if (wrapper != null) {
                try {
                    wrapper.getCustomSqlSegment();
                    Map<String, Object> params = wrapper.getParamNameValuePairs();
                    if (params != null && !params.isEmpty()) {
                        for (MoneyRequestEntity req : requestDb.values()) {
                            if (params.containsValue(req.getRequestId())) {
                                return req;
                            }
                        }
                        return null;
                    }
                } catch (Exception ignored) {}
            }
            return requestDb.values().stream().findFirst().orElse(null);
        });

        projectionMapper = mock(MoneyProjectionMapper.class);
        when(projectionMapper.insert(any(MoneyProjectionEntity.class))).thenAnswer((Answer<Integer>) invocation -> {
            MoneyProjectionEntity p = invocation.getArgument(0);
            projectionDb.add(p);
            return 1;
        });
        when(projectionMapper.selectOne(any())).thenAnswer((Answer<MoneyProjectionEntity>) invocation -> {
            // 支持按 audienceScope 倒序查找最新 revision
            LambdaQueryWrapper<MoneyProjectionEntity> wrapper = invocation.getArgument(0);
            String targetScope = null;
            if (wrapper != null) {
                try {
                    wrapper.getCustomSqlSegment();
                    Map<String, Object> params = wrapper.getParamNameValuePairs();
                    if (params != null) {
                        if (params.containsValue("FINANCE")) targetScope = "FINANCE";
                        else if (params.containsValue("BUYER")) targetScope = "BUYER";
                    }
                } catch (Exception ignored) {}
            }
            for (int i = projectionDb.size() - 1; i >= 0; i--) {
                MoneyProjectionEntity p = projectionDb.get(i);
                if (targetScope == null || targetScope.equalsIgnoreCase(p.getAudienceScope())) {
                    return p;
                }
            }
            return projectionDb.isEmpty() ? null : projectionDb.get(projectionDb.size() - 1);
        });

        eventMapper = mock(MoneyEventMapper.class);
        when(eventMapper.insert(any(MoneyEventEntity.class))).thenAnswer((Answer<Integer>) invocation -> {
            eventDb.add(invocation.getArgument(0));
            return 1;
        });

        snapshotMapper = mock(MoneySnapshotMapper.class);
        when(snapshotMapper.insert(any(MoneySnapshotEntity.class))).thenAnswer((Answer<Integer>) invocation -> {
            snapshotDb.add(invocation.getArgument(0));
            return 1;
        });
        when(snapshotMapper.selectList(any())).thenAnswer((Answer<List<MoneySnapshotEntity>>) invocation -> {
            return new ArrayList<>(snapshotDb);
        });

        toolCallMapper = mock(MoneyToolCallMapper.class);
        when(toolCallMapper.insert(any(MoneyToolCallEntity.class))).thenAnswer((Answer<Integer>) invocation -> {
            toolCallDb.add(invocation.getArgument(0));
            return 1;
        });
        when(toolCallMapper.selectList(any())).thenAnswer((Answer<List<MoneyToolCallEntity>>) invocation -> {
            return new ArrayList<>(toolCallDb);
        });

        grantMapper = mock(MoneyGrantMapper.class);
        when(grantMapper.insert(any(MoneyGrantEntity.class))).thenAnswer((Answer<Integer>) invocation -> {
            MoneyGrantEntity g = invocation.getArgument(0);
            grantDb.put(g.getGrantNo(), g);
            return 1;
        });
        when(grantMapper.selectOne(any())).thenAnswer((Answer<MoneyGrantEntity>) invocation -> {
            MoneyGrantEntity g = grantDb.values().stream().findFirst().orElse(null);
            if (g == null) {
                return MoneyGrantEntity.builder()
                        .grantNo("GRANT_DEFAULT")
                        .state("ACTIVE")
                        .expiresAt(LocalDateTime.now(ZoneOffset.UTC).plusDays(1))
                        .build();
            }
            return g;
        });

        taskAccessMapper = mock(MoneyTaskAccessMapper.class);
        when(taskAccessMapper.insert(any(MoneyTaskAccessEntity.class))).thenAnswer((Answer<Integer>) invocation -> {
            MoneyTaskAccessEntity a = invocation.getArgument(0);
            taskAccessDb.put(a.getTaskNo(), a);
            return 1;
        });

        inputRequestMapper = mock(MoneyInputRequestMapper.class);
        when(inputRequestMapper.insert(any(MoneyInputRequestEntity.class))).thenAnswer((Answer<Integer>) invocation -> {
            MoneyInputRequestEntity req = invocation.getArgument(0);
            inputRequestDb.put(req.getInputNo(), req);
            return 1;
        });
        when(inputRequestMapper.updateById(any(MoneyInputRequestEntity.class))).thenAnswer((Answer<Integer>) invocation -> {
            MoneyInputRequestEntity req = invocation.getArgument(0);
            inputRequestDb.put(req.getInputNo(), req);
            return 1;
        });
        when(inputRequestMapper.selectOne(any())).thenAnswer((Answer<MoneyInputRequestEntity>) invocation -> {
            return inputRequestDb.values().stream().findFirst().orElse(null);
        });

        taskInputMapper = mock(MoneyTaskInputMapper.class);
        when(taskInputMapper.insert(any(MoneyTaskInputEntity.class))).thenAnswer((Answer<Integer>) invocation -> {
            taskInputDb.add(invocation.getArgument(0));
            return 1;
        });

        outboxMapper = mock(MoneyOutboxMapper.class);
        when(outboxMapper.insert(any(MoneyOutboxEntity.class))).thenReturn(1);

        // 外部依赖 Client Mock
        orderQueryClient = mock(OrderMoneyQueryClient.class);
        fundQueryClient = mock(UserFundQueryClient.class);

        // 默认模拟调查对象解析、渠道与订单正常成功出账
        ResolveMoneySubjectResult resolveRes = new ResolveMoneySubjectResult(
                "T20261101001", "BUYER:1001", "SELLER:200", "BUYER_VIEW",
                List.of("ATT-001"), List.of(), "scope-proof-01", "fp-1", "SUCCESS", null);
        when(orderQueryClient.resolveSubject(any(), any())).thenReturn(resolveRes);

        MoneyOrderSnapshot orderSnapshot = new MoneyOrderSnapshot(
                "ORDER:T20261101001", "T20261101001", "ATT-001", "PAID",
                List.of(), List.of(), List.of(), null, List.of(),
                new CoverageInfo(Map.of("ORDER", "OK"), true, false, null, "Normal"),
                "fp-snap", "2026-10-31T10:00:00Z");
        when(orderQueryClient.getSnapshot(any(), any())).thenReturn(orderSnapshot);

        MoneyChannelObservation channelObs = new MoneyChannelObservation(
                "OBS_1", "ORDER:T20261101001", "ATT-001", null, "SUCCESS", true, "SUCCESS",
                new BigDecimal("99.00"), "CNY", "MERCH_1", "TRADE_1", null, null, null,
                "MOCK", true, true, true, "AUDIT_1", null);
        when(orderQueryClient.observeChannel(any(), any())).thenReturn(channelObs);

        grantService = new MoneyGrantService(grantMapper, taskAccessMapper, taskMapper);
        MockMoneyModelClient modelClient = new MockMoneyModelClient();
        MoneyPlanValidator planValidator = new MoneyPlanValidator();

        investigationGraph = new MoneyInvestigationGraph(
                orderQueryClient, fundQueryClient, caseMapper, taskMapper, reportMapper,
                inputRequestMapper, projectionMapper, eventMapper, grantService,
                modelClient, planValidator, objectMapper, snapshotMapper, toolCallMapper, outboxMapper,
                null, metrics);

        caseService = new MoneyCaseService(
                caseMapper, taskMapper, reportMapper, requestMapper, projectionMapper,
                eventMapper, grantService, investigationGraph, null, metrics);

        taskWorker = new MoneyTaskWorker(taskMapper, caseMapper, investigationGraph);
        caseService.setTaskWorker(taskWorker);
        inputService = new MoneyInputService(
                taskMapper, caseMapper, taskInputMapper, inputRequestMapper, requestMapper, taskWorker);

        replayService = new MoneyReplayService(
                taskMapper, caseMapper, reportMapper, snapshotMapper, toolCallMapper, metrics, objectMapper);
    }

    @Test
    @DisplayName("验收测试 1: active_dedupe_key 并发去重锁与竞争测试")
    void testConcurrentDeduplicationLock() throws Exception {
        int threadCount = 8;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch finishLatch = new CountDownLatch(threadCount);

        List<MoneyCaseService.CaseExecutionResult> results = new CopyOnWriteArrayList<>();
        AtomicInteger conflictCount = new AtomicInteger(0);

        String scopeKey = "ORDER:T20261101001";
        String problemFamily = "PAYMENT_CONFIRMATION";
        String targetRef = "T20261101001";

        for (int i = 0; i < threadCount; i++) {
            final String reqId = "REQ-CONCURRENT-" + i;
            executor.submit(() -> {
                try {
                    startLatch.await();
                    // 同步并发提交同一问题的主题建案请求
                    MoneyCaseService.CaseExecutionResult res = caseService.createOrAttachCase(
                            scopeKey, problemFamily, targetRef, "CUSTOMER", 1001L, "BUYER",
                            "mall-agent-service", reqId, null);
                    results.add(res);
                } catch (Exception e) {
                    if (e.getMessage() != null && e.getMessage().contains("Duplicate entry")) {
                        conflictCount.incrementAndGet();
                    }
                } finally {
                    finishLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        assertTrue(finishLatch.await(5, TimeUnit.SECONDS), "并发建案测试超时");
        executor.shutdown();

        // 验证：所有成功建案或复用的结果中，caseNo 必须全局统一，系统内至多存在 1 个活跃 Case
        assertFalse(results.isEmpty(), "至少应有一个成功建案");
        String standardCaseNo = results.get(0).caseEntity().getCaseNo();
        for (MoneyCaseService.CaseExecutionResult r : results) {
            assertEquals(standardCaseNo, r.caseEntity().getCaseNo(),
                    "并发提交相同 (scope, problem, targetRef) 必须复用同一个案号");
        }
        assertEquals(1, caseDb.size(), "持久化数据库中只允许存在唯一个案件记录");
        for (int retry = 0; retry < 50 && !"RESOLVED".equals(caseDb.get(standardCaseNo).getState()); retry++) {
            Thread.sleep(50);
        }
        assertEquals("RESOLVED", caseDb.get(standardCaseNo).getState(), "正常调查完毕案件进入 RESOLVED");
        assertNull(caseDb.get(standardCaseNo).getActiveDedupeKey(), "正常解决后释放活动去重键");
    }

    @Test
    @DisplayName("验收测试 2: 断网重试后幂等请求重放测试")
    void testIdempotentRequestReplayAfterNetworkDrop() {
        String requestId = "REQ-NETWORK-DROP-001";
        String scopeKey = "ORDER:T20261101002";
        String problemFamily = "PAYMENT_CONFIRMATION";
        String targetRef = "T20261101002";

        // 1. 首次提交请求（模拟客户端发出并成功受理）
        MoneyCaseService.CaseExecutionResult firstResult = caseService.createOrAttachCase(
                scopeKey, problemFamily, targetRef, "CUSTOMER", 1001L, "BUYER",
                "mall-agent-service", requestId, null);
        assertNotNull(firstResult);
        String caseNo = firstResult.caseEntity().getCaseNo();
        String taskNo = firstResult.taskEntity().getTaskNo();
        String contextId = firstResult.taskEntity().getContextId();
        assertTrue(firstResult.isNew(), "首次调用必须标识为新建案");

        // 2. 模拟网络丢包/超时重试：客户端使用完全相同的 requestId 重新发送
        long initialIdempotencyHits = metrics.getIdempotencyHitCount();
        MoneyCaseService.CaseExecutionResult replayResult = caseService.createOrAttachCase(
                scopeKey, problemFamily, targetRef, "CUSTOMER", 1001L, "BUYER",
                "mall-agent-service", requestId, null);

        assertNotNull(replayResult);
        assertFalse(replayResult.isNew(), "幂等重试必须标识为复用已有回执");
        assertEquals(caseNo, replayResult.caseEntity().getCaseNo(), "案号必须绝对一致");
        assertEquals(taskNo, replayResult.taskEntity().getTaskNo(), "任务号必须绝对一致");
        assertEquals(contextId, replayResult.taskEntity().getContextId(), "ContextId 必须绝对一致");
        assertEquals(initialIdempotencyHits + 1, metrics.getIdempotencyHitCount(), "指标系统必须记录幂等命中");

        // 3. 校验防篡改冲突：恶意或异常客户端用相同 requestId 发送不同内容 -> 必须抛出 MONEY_IDEMPOTENCY_CONFLICT
        MoneyAgentException conflictEx = assertThrows(MoneyAgentException.class, () -> {
            caseService.createOrAttachCase(
                    "ORDER:TAMPERED_DIFFERENT_ORDER", problemFamily, "TAMPERED", "CUSTOMER",
                    1001L, "BUYER", "mall-agent-service", requestId, null);
        });
        assertEquals(MoneyErrorCodes.MONEY_IDEMPOTENCY_CONFLICT, conflictEx.getCode(),
                "相同 requestId 载荷篡改必须拒绝并报错 MONEY_IDEMPOTENCY_CONFLICT");
    }

    @Test
    @DisplayName("验收测试 3: 补证竞争测试（版本冲突与重复补证防重）")
    void testSubmitInputVersionConflictAndDuplicateDefense() {
        String taskNo = "MT_TEST_INPUT_001";
        String caseNo = "MC_TEST_INPUT_001";
        String inputNo = "INP_REQ_001";

        // 初始化任务与待补证记录 (inputVersion = 1)
        MoneyTaskEntity task = MoneyTaskEntity.builder()
                .taskNo(taskNo)
                .caseNo(caseNo)
                .state("WAITING_INPUT")
                .version(1L)
                .fencingToken(1L)
                .build();
        taskDb.put(taskNo, task);

        MoneyCaseEntity caseEntity = MoneyCaseEntity.builder()
                .caseNo(caseNo)
                .scopeKey("ORDER:T20261101001")
                .problemFamily("PAYMENT_CONFIRMATION")
                .currentTaskNo(taskNo)
                .state("NEED_INPUT")
                .version(1L)
                .build();
        caseDb.put(caseNo, caseEntity);

        MoneyInputRequestEntity inputReq = MoneyInputRequestEntity.builder()
                .inputNo(inputNo)
                .taskNo(taskNo)
                .inputVersion(1)
                .state("WAITING")
                .build();
        inputRequestDb.put(inputNo, inputReq);

        // 场景 A: 乐观锁版本冲突测试（客户端提交 expectedVersion=2，与服务端版本 1 不符）
        MoneyAdditionalInput conflictInput = MoneyAdditionalInput.builder()
                .taskNo(taskNo)
                .inputRequestNo(inputNo)
                .expectedInputVersion(2L)
                .answers(Map.of("proofImage", "https://img.mall.com/proof.png"))
                .build();

        MoneyAgentException versionEx = assertThrows(MoneyAgentException.class, () -> {
            inputService.submitAdditionalInput("mall-agent-service", "REQ-INP-01", conflictInput);
        });
        assertEquals(MoneyErrorCodes.MONEY_VERSION_CONFLICT, versionEx.getCode(),
                "补证预期版本不匹配时必须拒绝");

        // 场景 B: 正常补证并检查状态恢复
        MoneyAdditionalInput validInput = MoneyAdditionalInput.builder()
                .taskNo(taskNo)
                .inputRequestNo(inputNo)
                .expectedInputVersion(1L)
                .answers(Map.of("payVoucher", "VOUCHER_123456"))
                .build();

        boolean submitOk = inputService.submitAdditionalInput("mall-agent-service", "REQ-INP-02", validInput);
        assertTrue(submitOk, "有效补证必须成功受理");

        // 校验状态恢复与版本递增
        MoneyTaskEntity updatedTask = taskDb.get(taskNo);
        assertTrue(List.of("SUBMITTED", "WORKING", "COMPLETED").contains(updatedTask.getState()),
                "补证后任务必须重新恢复为 SUBMITTED 或由 Worker 调度执行");
        assertTrue(updatedTask.getVersion() > 1L, "任务乐观锁版本必须自增");
        assertTrue(updatedTask.getFencingToken() > 1L, "任务 fencingToken 必须自增以防旧 Worker 竞争");

        // 场景 C: 重复补证幂等测试（相同 requestId 重放必须静默返回 true，不产生冗余副本）
        int initialTaskInputs = taskInputDb.size();
        boolean duplicateOk = inputService.submitAdditionalInput("mall-agent-service", "REQ-INP-02", validInput);
        assertTrue(duplicateOk, "相同 requestId 重复补证必须幂等成功");
        assertEquals(initialTaskInputs, taskInputDb.size(), "幂等补证不得重复插入材料实体");

        // 场景 D: 终态任务禁止补证测试
        updatedTask.setState("COMPLETED");
        taskDb.put(taskNo, updatedTask);
        MoneyAgentException terminalEx = assertThrows(MoneyAgentException.class, () -> {
            inputService.submitAdditionalInput("mall-agent-service", "REQ-INP-03", validInput);
        });
        assertEquals(MoneyErrorCodes.MONEY_FORBIDDEN, terminalEx.getCode(), "终态任务严禁补证");
    }

    @Test
    @DisplayName("验收测试 4: 受众投影隔离测试（BUYER 遮蔽 vs FINANCE 完整）")
    void testAudienceProjectionIsolation() throws Exception {
        String scopeKey = "ORDER:T20261101003";
        String problemFamily = "PAYMENT_CONFIRMATION";

        // 执行全链路资金调查
        MoneyCaseService.CaseExecutionResult executionResult = caseService.createOrAttachCase(
                scopeKey, problemFamily, "T20261101003", "CUSTOMER", 2002L, "BUYER",
                "mall-agent-service", "REQ-AUDIENCE-001", null);

        assertNotNull(executionResult);
        String caseNo = executionResult.caseEntity().getCaseNo();

        // 等待异步 Worker 运行完成并发布报告
        for (int i = 0; i < 50; i++) {
            MoneyProjectionEntity fp = caseService.getProjection(caseNo, "FINANCE");
            if (fp != null && fp.getPublicJson() != null && fp.getPublicJson().contains("internalReport")) {
                break;
            }
            Thread.sleep(50);
        }

        // 1. 获取并核验 BUYER 受众投影
        MoneyProjectionEntity buyerProjection = caseService.getProjection(caseNo, "BUYER");
        assertNotNull(buyerProjection, "系统必须自动生成 BUYER 投影");
        String buyerJson = buyerProjection.getPublicJson();
        assertNotNull(buyerJson);

        Map<String, Object> buyerData = objectMapper.readValue(buyerJson, Map.class);
        // BUYER 允许字段
        assertTrue(buyerData.containsKey("caseNo"));
        assertTrue(buyerData.containsKey("orderNo"));
        assertTrue(buyerData.containsKey("resolutionOutcome"));
        assertTrue(buyerData.containsKey("publicSummary"));

        // BUYER 必须严厉遮蔽的敏感字段：内部证据、渠道明细、商户秘钥、快照指纹等
        assertFalse(buyerData.containsKey("internalReport"), "BUYER 投影严禁包含内部审查报告 internalReport");
        assertFalse(buyerData.containsKey("attemptObservations"), "BUYER 投影严禁包含原始渠道观测明细 attemptObservations");
        assertFalse(buyerData.containsKey("factsSnapshotFingerprint"), "BUYER 投影严禁暴露底层不可变快照指纹");
        assertFalse(buyerData.containsKey("grantRef"), "BUYER 投影严禁暴露内部授权票据 grantRef");

        // 2. 获取并核验 FINANCE 受众投影
        MoneyProjectionEntity financeProjection = caseService.getProjection(caseNo, "FINANCE");
        assertNotNull(financeProjection, "系统必须自动生成面向财务的审计级 FINANCE 投影");
        String financeJson = financeProjection.getPublicJson();
        assertNotNull(financeJson);

        Map<String, Object> financeData = objectMapper.readValue(financeJson, Map.class);
        // FINANCE 拥有完整证据链和内部核对信息
        assertTrue(financeData.containsKey("internalReport"), "FINANCE 投影必须包含完整的内部审计报告");
        assertTrue(financeData.containsKey("attemptObservations"), "FINANCE 投影必须包含全量渠道观测结果");
        assertTrue(financeData.containsKey("factsSnapshotFingerprint"), "FINANCE 投影必须包含不可变快照事实指纹");
        assertTrue(financeData.containsKey("grantRef"), "FINANCE 投影必须具备完整的审计授权凭据");
    }

    @Test
    @DisplayName("验收测试 5: 全链路链路追踪传播、运营指标统计与快照回放服务测试")
    void testTracePropagationAndOperationalMetricsAndReplay() throws Exception {
        String expectedTraceId = "TRACE-ACCEPTED-TEST-9999";
        String expectedRunNo = "RUN-CUST-8888";
        String expectedDispatchNo = "MD-DISPATCH-7777";

        MoneyInvestigationRequest req = MoneyInvestigationRequest.builder()
                .requestId("REQ-TRACE-REPLAY-001")
                .origin("DOMAIN_EVENT")
                .problemFamily(MoneyProblemFamily.PAYMENT_CONFIRMATION)
                .subject(MoneyInvestigationRequest.Subject.builder()
                        .orderNo("T20261101004")
                        .scopeKey("ORDER:T20261101004")
                        .build())
                .callerContext(MoneyInvestigationRequest.CallerContext.builder()
                        .traceId(expectedTraceId)
                        .customerRunNo(expectedRunNo)
                        .dispatchNo(expectedDispatchNo)
                        .build())
                .build();

        // 触发调查建案
        MoneyCaseService.CaseExecutionResult executionResult = caseService.startInvestigation(req);
        assertNotNull(executionResult);
        String taskNo = executionResult.taskEntity().getTaskNo();

        // 1. 验证全链路追踪信息完整落地在 Checkpoint 中，未被篡改为伪造的 TRACE_taskNo
        MoneyTaskEntity createdTask = taskDb.get(taskNo);
        assertNotNull(createdTask);
        assertNotNull(createdTask.getCheckpointJson());
        assertTrue(createdTask.getCheckpointJson().contains(expectedTraceId),
                "Checkpoint 必须完整保留上游传递的 traceId: " + expectedTraceId);
        assertTrue(createdTask.getCheckpointJson().contains(expectedRunNo),
                "Checkpoint 必须完整保留上游 customerRunNo");
        assertTrue(createdTask.getCheckpointJson().contains(expectedDispatchNo),
                "Checkpoint 必须完整保留上游 dispatchNo");

        // 等待异步 Worker 运行完成
        for (int i = 0; i < 50; i++) {
            if (metrics.getTaskCompletedCount() > 0) {
                break;
            }
            Thread.sleep(50);
        }

        // 2. 验证运营指标收集
        assertTrue(metrics.getTaskStartedCount() > 0, "指标系统必须记录任务启动数");
        assertTrue(metrics.getTaskCompletedCount() > 0, "指标系统必须记录任务完成数");
        assertTrue(metrics.getToolCallCount() > 0, "指标系统必须记录工具调用计数");
        Map<String, Object> snapshot = metrics.getMetricsSnapshot();
        assertNotNull(snapshot);
        assertTrue((Long) snapshot.get("taskCompleted") >= 1L);

        // 3. 执行不可变快照离线回放评估（MoneyReplayService），不触发任何外部写操作
        MoneyReplayResult replayResult = replayService.replayInvestigation(taskNo);
        assertNotNull(replayResult);
        assertEquals(taskNo, replayResult.getTaskNo());
        assertTrue(replayResult.isMatch(), "回放纯净规则推导结果必须与原始执行结果一致");
        assertTrue(replayResult.getSnapshotCount() >= 1, "回放必须正确读取不可变事实快照");
        assertEquals(1, metrics.getReplayCount(), "指标系统必须记录回放执行次数");
    }
}
