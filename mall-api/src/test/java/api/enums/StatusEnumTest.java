package api.enums;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class StatusEnumTest {
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void codeRoundTripsAsJsonString() throws Exception {
        Assertions.assertEquals("\"WAIT_PAY\"", objectMapper.writeValueAsString(OrderStatus.WAIT_PAY));
        Assertions.assertEquals(OrderStatus.SETTLING,
                objectMapper.readValue("\"SETTLING\"", OrderStatus.class));
    }

    @Test
    void unknownCodeIsToleratedByOrNullAndRejectedByFromCode() {
        Assertions.assertNull(OrderStatus.fromCodeOrNull("NOT_A_STATUS"));
        Assertions.assertNull(OrderStatus.fromCodeOrNull(null));
        Assertions.assertThrows(IllegalArgumentException.class,
                () -> OrderStatus.fromCode("NOT_A_STATUS"));
    }

    @Test
    void orderStatusMatrixMatchesDocumentedEdges() {
        Assertions.assertTrue(OrderStatus.WAIT_PAY.canTransitionTo(OrderStatus.PAID));
        Assertions.assertTrue(OrderStatus.WAIT_PAY.canTransitionTo(OrderStatus.CANCELLED));
        Assertions.assertTrue(OrderStatus.PAID.canTransitionTo(OrderStatus.DELIVERED));
        Assertions.assertTrue(OrderStatus.DELIVERED.canTransitionTo(OrderStatus.CONFIRMED));
        Assertions.assertTrue(OrderStatus.CONFIRMED.canTransitionTo(OrderStatus.SETTLING));
        Assertions.assertTrue(OrderStatus.SETTLING.canTransitionTo(OrderStatus.SETTLED));
        Assertions.assertTrue(OrderStatus.SETTLED.isTerminal());
        Assertions.assertTrue(OrderStatus.CANCELLED.isTerminal());

        Assertions.assertFalse(OrderStatus.WAIT_PAY.canTransitionTo(OrderStatus.SETTLED));
        Assertions.assertFalse(OrderStatus.SETTLED.canTransitionTo(OrderStatus.WAIT_PAY));
        Assertions.assertFalse(OrderStatus.PAID.canTransitionTo(null));
    }

    @Test
    void paymentEscrowAndRiskCaseMatricesAreEnforced() {
        Assertions.assertTrue(PayStatus.INIT.canTransitionTo(PayStatus.PAYING));
        Assertions.assertTrue(PayStatus.PAYING.canTransitionTo(PayStatus.SUCCESS));
        Assertions.assertTrue(PayStatus.PAYING.canTransitionTo(PayStatus.TIMEOUT));
        Assertions.assertTrue(PayStatus.TIMEOUT.isTerminal());
        Assertions.assertFalse(PayStatus.SUCCESS.canTransitionTo(PayStatus.PAYING));

        Assertions.assertTrue(EscrowStatus.NONE.canTransitionTo(EscrowStatus.FROZEN));
        Assertions.assertTrue(EscrowStatus.FROZEN.canTransitionTo(EscrowStatus.SETTLE_PENDING));
        Assertions.assertTrue(EscrowStatus.SETTLE_PENDING.canTransitionTo(EscrowStatus.SETTLED));
        Assertions.assertFalse(EscrowStatus.SETTLED.canTransitionTo(EscrowStatus.FROZEN));

        Assertions.assertTrue(RiskCaseStatus.OPEN.canTransitionTo(RiskCaseStatus.PROCESSING));
        Assertions.assertTrue(RiskCaseStatus.PROCESSING.canTransitionTo(RiskCaseStatus.RESOLVED));
        Assertions.assertTrue(RiskCaseStatus.RESOLVED.canTransitionTo(RiskCaseStatus.CLOSED));
        Assertions.assertTrue(RiskCaseStatus.CLOSED.canTransitionTo(RiskCaseStatus.OPEN));
        Assertions.assertFalse(RiskCaseStatus.OPEN.canTransitionTo(RiskCaseStatus.CLOSED));
    }

    @Test
    void riskLevelUsesExplicitRank() {
        Assertions.assertTrue(RiskLevel.CRITICAL.atLeast(RiskLevel.HIGH));
        Assertions.assertTrue(RiskLevel.HIGH.atLeast(RiskLevel.HIGH));
        Assertions.assertFalse(RiskLevel.LOW.atLeast(RiskLevel.MEDIUM));
        Assertions.assertFalse(RiskLevel.LOW.atLeast(null));
        Assertions.assertEquals(4, RiskLevel.CRITICAL.getRank());
    }

    @Test
    void riskCommandStatusMatchesMapperLifecycle() {
        Assertions.assertTrue(RiskCommandStatus.NONE.canTransitionTo(RiskCommandStatus.PENDING_SEND));
        Assertions.assertTrue(RiskCommandStatus.PENDING_SEND.canTransitionTo(RiskCommandStatus.SENT));
        Assertions.assertTrue(RiskCommandStatus.SENT.canTransitionTo(RiskCommandStatus.COMMAND_FAILED));
        Assertions.assertTrue(RiskCommandStatus.COMMAND_FAILED.canTransitionTo(RiskCommandStatus.PENDING_SEND));
        Assertions.assertFalse(RiskCommandStatus.SUCCESS.canTransitionTo(RiskCommandStatus.PENDING_SEND));
        Assertions.assertTrue(RiskCommandStatus.DEAD_LETTER.isTerminal());
    }
}
