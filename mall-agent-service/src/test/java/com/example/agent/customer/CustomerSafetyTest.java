package com.example.agent.customer;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import static com.example.agent.customer.CustomerJson.*;
import static org.junit.jupiter.api.Assertions.*;

class CustomerSafetyTest {
    @Test void toolAndDocumentCapabilitiesCannotBeWidened() {
        assertThrows(RuntimeException.class,()->CustomerToolPolicy.require("RULE_AGENT","queryOrder"));
        assertThrows(RuntimeException.class,()->CustomerToolPolicy.require("RESPONSE_AGENT","searchKnowledge"));
        assertThrows(RuntimeException.class,()->CustomerToolPolicy.documentTypes("AFTER_SALE_AGENT",List.of("PRODUCT")));
        assertThrows(RuntimeException.class,()->CustomerToolPolicy.documentTypes("RULE_AGENT",List.of("RISK")));
        assertEquals(List.of("AFTER_SALE","RULE","FAQ"),CustomerToolPolicy.documentTypes("AFTER_SALE_AGENT",null));
    }
    @Test void cancellationInterruptsWorkAndDropsLateValues() throws Exception {
        AtomicBoolean interrupted=new AtomicBoolean();CountDownLatch done=new CountDownLatch(1);
        var budget=new CustomerBudget(1000);
        assertThrows(RuntimeException.class,()->budget.call(30,()->{
            try { Thread.sleep(5000); } catch(InterruptedException cancelled) { interrupted.set(true); }
            finally { done.countDown(); }
            return "late result";
        }));
        assertTrue(done.await(1,TimeUnit.SECONDS));assertTrue(interrupted.get());
    }
    @Test void nestedBudgetCannotExtendParentDeadline() {
        var budget=new CustomerBudget(500);
        assertTrue(budget.call(200,()->new CustomerBudget(90000).remaining())<=200);
    }
    @Test void unknownOrUncitedPolicyResultIsRejected() {
        var task=CustomerMaster.task("WITHDRAW_RULE","提现规则","r:i1",.9);
        var result=object().put("intentInstanceId","r:i1").put("answer","明天到账").put("resolutionState","COMPLETED").put("policyAnswer",true);
        result.set("citations",array());result.set("evidence",array());
        assertThrows(RuntimeException.class,()->CustomerOutputGuard.validate(task,result));
    }
    @Test void sensitivePayloadFieldsAreRedactedRecursively() {
        var result=object();result.set("citations",array().add(object().put("content","密码：abc123 邮箱 user@example.com")));
        CustomerOutputGuard.redactPayload(result);
        assertFalse(result.toString().contains("abc123"));assertFalse(result.toString().contains("user@example.com"));
    }
    @Test void riskSurvivesNegatedHumanRequest() {
        assertTrue(CustomerMaster.human("不要转人工，但账号被盗了"));
        assertFalse(CustomerMaster.human("不需要转人工"));
        assertTrue(CustomerMaster.human("请转人工"));
    }
}
