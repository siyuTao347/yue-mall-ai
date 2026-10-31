package com.example.user.service;

import api.agent.money.*;
import api.exception.ForbiddenException;
import com.example.user.entity.FundBusinessRef;
import com.example.user.entity.FundFlow;
import com.example.user.entity.FundTransaction;
import com.example.user.mapper.FundBusinessRefMapper;
import com.example.user.mapper.FundFlowMapper;
import com.example.user.mapper.FundTransactionMapper;
import com.example.user.provider.MoneyFundQueryDubboServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class MoneyFundQueryDubboServiceImplTest {

    private FundTransactionMapper transactionMapper;
    private FundFlowMapper flowMapper;
    private FundBusinessRefMapper businessRefMapper;
    private FundService fundService;
    private MoneyFundQueryDubboServiceImpl service;

    @BeforeEach
    void setUp() {
        transactionMapper = mock(FundTransactionMapper.class);
        flowMapper = mock(FundFlowMapper.class);
        businessRefMapper = mock(FundBusinessRefMapper.class);
        fundService = mock(FundService.class);
        service = new MoneyFundQueryDubboServiceImpl(transactionMapper, flowMapper, businessRefMapper, fundService);
    }

    private MoneyAccessContext buildValidAccess(Long userId, String role, String action) {
        String svcProof = MoneySecuritySupport.createServiceIdentityProof("mall-money-agent-service", "mall-user-service");
        String delProof = MoneySecuritySupport.createDelegationProof(
                userId, role, null, "mall-user-service", List.of(action), "GRANT_USER_01");
        return new MoneyAccessContext(
                svcProof, delProof, null, action, "REQ_001", "TRACE_001",
                "mall-money-agent-service", userId, role
        );
    }

    @Test
    @DisplayName("R01验证：缺少凭证或范围证明无效时统一拒绝访问")
    void testRejectsInvalidTokensAndScopeProof() {
        // access = null
        MoneyFundQuery query = new MoneyFundQuery("some-token", "T001", List.of(), List.of(), List.of());
        assertThrows(ForbiddenException.class, () -> service.getSnapshot(null, query));

        // access 空票据
        MoneyAccessContext emptyAccess = new MoneyAccessContext(null, null, null, "QUERY_FUND_SNAPSHOT", "R1", "T1", "agent", 100L, "BUYER");
        assertThrows(ForbiddenException.class, () -> service.getSnapshot(emptyAccess, query));

        // 范围凭据无效
        MoneyAccessContext validAccess = buildValidAccess(100L, "BUYER", "QUERY_FUND_SNAPSHOT");
        MoneyFundQuery invalidScopeQuery = new MoneyFundQuery("invalid-token", "T001", List.of(), List.of(), List.of());
        assertThrows(ForbiddenException.class, () -> service.getSnapshot(validAccess, invalidScopeQuery));
    }

    @Test
    @DisplayName("R01验证：买家角色越权查询他人订单时应拒绝")
    void testRejectsBuyerMismatch() {
        String proof = MoneySecuritySupport.createScopeProof("T001", 999L, 888L, List.of("PA_1"), List.of(), "fp1", "mall-user-service");
        MoneyAccessContext access = buildValidAccess(100L, "BUYER", "QUERY_FUND_SNAPSHOT");
        MoneyFundQuery query = new MoneyFundQuery(proof, "T001", List.of(), List.of(), List.of());

        assertThrows(ForbiddenException.class, () -> service.getSnapshot(access, query));
    }

    @Test
    @DisplayName("合法凭证下成功返回资金事务、流水与业务引用")
    void testSuccessfulQueryWithValidScopeProof() {
        String proof = MoneySecuritySupport.createScopeProof("T001", 100L, 888L, List.of("PA_01"), List.of("RF_01"), "fp1", "mall-user-service");
        MoneyAccessContext access = buildValidAccess(100L, "BUYER", "QUERY_FUND_SNAPSHOT");
        MoneyFundQuery query = new MoneyFundQuery(proof, "T001", List.of("PA_01"), List.of("RF_01"), List.of());

        FundTransaction tx = new FundTransaction(1L, "TX_001", "PAY_FREEZE", "T001", null, 1L, 100L, new BigDecimal("100.00"), "SUCCESS", "FUND_FREEZE:T001", "Remark", LocalDateTime.now());
        when(transactionMapper.selectList(any())).thenReturn(List.of(tx));

        FundFlow flow = new FundFlow(1L, "TX_001", "PLATFORM", null, "ESCROW", "CREDIT", new BigDecimal("100.00"), null, "Memo", LocalDateTime.now());
        when(flowMapper.selectList(any())).thenReturn(List.of(flow));

        FundBusinessRef ref = new FundBusinessRef(1L, "TX_001", "ORDER", "T001", "TRANSACTION_BIND", LocalDateTime.now());
        when(businessRefMapper.selectByRefTypeAndNos(any(), any())).thenReturn(List.of(ref));
        when(businessRefMapper.selectByTransactionNos(any())).thenReturn(List.of(ref));

        MoneyFundSnapshot snapshot = service.getSnapshot(access, query);

        assertNotNull(snapshot);
        assertEquals(1, snapshot.getTransactions().size());
        assertEquals("TX_001", snapshot.getTransactions().get(0).getTransactionNo());
        assertEquals(1, snapshot.getFlows().size());
        assertEquals("ESCROW", snapshot.getFlows().get(0).getAccountType());
        assertEquals(1, snapshot.getBusinessRefs().size());
        assertEquals("ORDER", snapshot.getBusinessRefs().get(0).getRefType());
        assertTrue(snapshot.getCoverage().isComplete());
    }

    @Test
    @DisplayName("R05验证：历史挂账/退款无 orderNo 流水通过精确幂等键精确命中，不漏查")
    void testHistoricalSuspenseTransactionFoundByIdempotencyKey() {
        // 范围证明包含 PA_SUSPENSE_01
        String proof = MoneySecuritySupport.createScopeProof("T001", 100L, 888L, List.of("PA_SUSPENSE_01"), List.of(), "fp1", "mall-user-service");
        MoneyAccessContext access = buildValidAccess(100L, "BUYER", "QUERY_FUND_SNAPSHOT");
        MoneyFundQuery query = new MoneyFundQuery(proof, "T001", List.of("PA_SUSPENSE_01"), List.of(), List.of());

        // 历史挂账事务无 orderNo，幂等键为 FUND_SUSPENSE_CREDIT:PA_SUSPENSE_01
        FundTransaction suspenseTx = new FundTransaction(
                2L, "TX_SUSPENSE_01", "SUSPENSE_CREDIT", null, null, null, null,
                new BigDecimal("50.00"), "SUCCESS", "FUND_SUSPENSE_CREDIT:PA_SUSPENSE_01", "挂账", LocalDateTime.of(2026, 10, 30, 10, 0)
        );
        when(transactionMapper.selectList(any())).thenReturn(List.of(suspenseTx));
        when(flowMapper.selectList(any())).thenReturn(List.of());
        when(businessRefMapper.selectByTransactionNos(any())).thenReturn(List.of());

        MoneyFundSnapshot snapshot = service.getSnapshot(access, query);

        assertNotNull(snapshot);
        assertEquals(1, snapshot.getTransactions().size());
        assertEquals("TX_SUSPENSE_01", snapshot.getTransactions().get(0).getTransactionNo());
        // 关键断言：未在 t_fund_business_ref 落库的历史事务，派生出 LEGACY_DERIVED 引用，不丢事实
        assertEquals(1, snapshot.getBusinessRefs().size());
        assertEquals("ATTEMPT", snapshot.getBusinessRefs().get(0).getRefType());
        assertEquals("PA_SUSPENSE_01", snapshot.getBusinessRefs().get(0).getRefNo());
        assertEquals("LEGACY_DERIVED", snapshot.getBusinessRefs().get(0).getLinkOrigin());
        // 覆盖状态反映 LEGACY_UNLINKED
        assertEquals(MoneyCoverageStatus.LEGACY_UNLINKED.name(), snapshot.getCoverage().getSources().get("BUSINESS_REF"));
        // R11: 检查 Asia/Shanghai 历史本地时间已格式化为带时区/UTC Instant
        assertNotNull(snapshot.getTransactions().get(0).getCreatedTime());
        assertTrue(snapshot.getTransactions().get(0).getCreatedTime().endsWith("Z"));
    }

    @Test
    @DisplayName("R08验证：查询为纯只读边界，绝对不调用 backfillLegacyReferencesIfMissing")
    void testReadOnlyBoundaryNoBackfillOnQuery() {
        String proof = MoneySecuritySupport.createScopeProof("T001", 100L, 888L, List.of("PA_01"), List.of(), "fp1", "mall-user-service");
        MoneyAccessContext access = buildValidAccess(100L, "BUYER", "QUERY_FUND_SNAPSHOT");
        MoneyFundQuery query = new MoneyFundQuery(proof, "T001", List.of("PA_01"), List.of(), List.of());

        FundTransaction tx = new FundTransaction(1L, "TX_001", "PAY_FREEZE", "T001", null, 1L, 100L, new BigDecimal("100.00"), "SUCCESS", "FUND_FREEZE:T001", "Remark", LocalDateTime.now());
        when(transactionMapper.selectList(any())).thenReturn(List.of(tx));

        service.getSnapshot(access, query);

        // 关键断言：纯只读查询严禁调用任何写入回填方法！
        verify(fundService, never()).backfillLegacyReferencesIfMissing(any());
        verify(businessRefMapper, never()).insert(any(FundBusinessRef.class));
    }
}
