package com.example.agent.customer;

import org.junit.jupiter.api.Test;
import com.example.agent.rag.embed.EmbeddingException;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.BadSqlGrammarException;
import java.nio.charset.StandardCharsets;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.example.agent.customer.CustomerJson.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class IntentReleaseTest {
    private IntentReleaseService.Snapshot valid(){Map<String,Object> h=new HashMap<>();h.put("release_no","R1");h.put("embedding_model","model-a");h.put("embedding_dim",1536);h.put("expected_intent_count",2);h.put("expected_example_count",2);h.put("default_similarity_threshold",.88);h.put("min_margin",.05);
        var items=new ArrayList<Map<String,Object>>();var examples=new ArrayList<Map<String,Object>>();int n=0;
        for(String code:List.of("ORDER_STATUS","SETTLEMENT")){String text="question"+(++n);items.add(new HashMap<>(Map.of("intent_code",code,"intent_version",1,"route_node","ORDER_AGENT","similarity_threshold",.88,"expected_example_count",1)));examples.add(new HashMap<>(Map.of("example_no","E"+n,"example_version",1,"intent_code",code,"intent_version",1,"example_text",text,"normalized_text",text,"example_hash",hash(text),"embedding_input_hash",hash("model-a|"+text))));}
        var s=new IntentReleaseService.Snapshot(h,items,examples);h.put("manifest_hash",IntentReleaseService.manifest(s));return s;}
    @Test void completeManifestAccepted(){assertDoesNotThrow(()->IntentReleaseService.validate(valid()));}
    @Test void absentCompetitorFailsInsteadOfReducingCatalog(){var s=valid();s.examples().removeLast();assertThrows(RuntimeException.class,()->IntentReleaseService.validate(s));}
    @Test void metadataMutationInvalidatesFrozenHash(){var s=valid();s.items().get(0).put("similarity_threshold",.6);assertThrows(RuntimeException.class,()->IntentReleaseService.validate(s));}
    @Test void staleModelHashAndWrongIntentVersionRejected(){var s=valid();s.examples().get(0).put("embedding_input_hash",hash("old-model|question1"));assertThrows(RuntimeException.class,()->IntentReleaseService.validate(s));var second=valid();second.examples().get(0).put("intent_version",2);assertThrows(RuntimeException.class,()->IntentReleaseService.validate(second));}
    @Test void canonicalManifestIndependentOfMapInsertionOrder(){var s=valid();var h=new TreeMap<>(s.header());assertEquals(IntentReleaseService.manifest(s),IntentReleaseService.manifest(new IntentReleaseService.Snapshot(h,s.items(),s.examples())));}

    @Test void providerFailureRetainsPhaseStatusAndUsefulReasonWithoutSecrets() {
        String body="{\"error\":{\"code\":\"InvalidParameter\",\"message\":\"batch must be <= 20; api_key=sk-testsecret; Authorization: Bearer jwtvalue\"},\"request\":\"private request\"}";
        var http=HttpClientErrorException.create(HttpStatus.BAD_REQUEST,"Bad Request",HttpHeaders.EMPTY,body.getBytes(StandardCharsets.UTF_8),StandardCharsets.UTF_8);
        var report=IntentReleaseService.buildFailure("EMBEDDING_EXAMPLES",new EmbeddingException("raw sensitive wrapper",http));
        assertFalse(report.path("passed").asBoolean());
        assertEquals("EMBEDDING_EXAMPLES",report.path("stage").asText());
        assertEquals(400,report.path("httpStatus").asInt());
        assertEquals("InvalidParameter",report.path("providerCode").asText());
        assertTrue(report.path("providerMessage").asText().contains("batch must be <= 20"));
        for(String secret:List.of("sk-testsecret","jwtvalue","private request","raw sensitive wrapper"))
            assertFalse(write(report).contains(secret));
    }

    @Test void databaseAndTimeoutFailuresAreDistinguishableWithoutRawSql() {
        var sql=new BadSqlGrammarException("write","secret SQL",new java.sql.SQLException("private database detail","42P01"));
        var report=IntentReleaseService.buildFailure("WRITING_VECTORS",sql);
        assertEquals("INTENT_DATABASE_ERROR",report.path("errorCode").asText());
        assertEquals("42P01",report.path("sqlState").asText());
        assertFalse(write(report).contains("secret SQL"));
        assertFalse(write(report).contains("private database detail"));
        var timeout=IntentReleaseService.buildFailure("EMBEDDING_EXAMPLES",new ResourceAccessException("private URL",new java.net.SocketTimeoutException("timeout")));
        assertEquals("INTENT_DEPENDENCY_TIMEOUT",timeout.path("errorCode").asText());
        assertFalse(write(timeout).contains("private URL"));
    }

    @Test void inputLeakageHasStableCodeAndInvalidProviderBodyIsNotCopied() {
        var leakage=IntentReleaseService.buildFailure("EVALUATION_INPUT",error(400,"INTENT_EVAL_LEAKAGE","评估问句不能与训练示例完全相同"));
        assertEquals("INTENT_EVAL_LEAKAGE",leakage.path("errorCode").asText());
        var http=HttpClientErrorException.create(HttpStatus.UNAUTHORIZED,"Unauthorized",HttpHeaders.EMPTY,"<html>private secret</html>".getBytes(StandardCharsets.UTF_8),StandardCharsets.UTF_8);
        var report=IntentReleaseService.buildFailure("EMBEDDING_EXAMPLES",http);
        assertEquals(401,report.path("httpStatus").asInt());
        assertFalse(write(report).contains("private secret"));
        var dimension=IntentReleaseService.buildFailure("EMBEDDING_EXAMPLES",new EmbeddingException("Embedding 维度不匹配: expected=1536, actual=1024"));
        assertEquals("INTENT_EMBEDDING_INVALID",dimension.path("errorCode").asText());
        assertTrue(dimension.path("error").asText().contains("actual=1024"));
    }

    @Test void failedEmbeddingPersistsUsefulReportAndNeverMakesVersionReady() throws Exception {
        var db=mock(CustomerDatabase.class);
        var mysql=mock(JdbcTemplate.class);
        ReflectionTestUtils.setField(db,"mysql",mysql);
        var embedding=mock(OnlineEmbedding.class);
        var service=spy(new IntentReleaseService(db,embedding,new CustomerProperties(),mock(IntentReleaseEvaluator.class)));
        try {
            doReturn(valid()).when(service).snapshot("R1");
            when(embedding.embedBatchWithUsage(anyList(),eq("model-a"),eq(1536)))
                .thenThrow(new EmbeddingException("upstream failed",new java.net.SocketTimeoutException()));
            service.build("R1",List.of(new IntentReleaseService.Evaluation("independent question","ORDER_STATUS")));
            verify(mysql).update(
                eq("UPDATE t_customer_service_intent_release SET status='FAILED',validation_report_json=? WHERE release_no=? AND status IN ('BUILDING','VALIDATING')"),
                argThat((String json)->read(json).path("stage").asText().equals("EMBEDDING_EXAMPLES")
                    &&read(json).path("errorCode").asText().equals("INTENT_DEPENDENCY_TIMEOUT")),
                eq("R1"));
            verifyNoMoreInteractions(mysql);
        } finally { service.close(); }
    }

    @Test void buildPersistsCascadeMetricsAndOnlyUsesFinalResultsForReady() throws Exception {
        var db=mock(CustomerDatabase.class);
        var mysql=mock(JdbcTemplate.class);
        ReflectionTestUtils.setField(db,"mysql",mysql);
        ReflectionTestUtils.setField(db,"vector",mock(JdbcTemplate.class));
        var manager=mock(PlatformTransactionManager.class);
        when(manager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        ReflectionTestUtils.setField(db,"tx",new TransactionTemplate(manager));
        when(mysql.queryForMap(anyString(),eq("R1"))).thenReturn(Map.of("status","BUILDING"));
        when(mysql.update("UPDATE t_customer_service_intent_release SET status='VALIDATING' WHERE release_no=? AND status='BUILDING'","R1")).thenReturn(1);
        var embedding=mock(OnlineEmbedding.class);
        when(embedding.embedBatchWithUsage(anyList(),eq("model-a"),eq(1536))).thenAnswer(call->{
            List<String> texts=call.getArgument(0);
            return new OnlineEmbedding.BatchResult(texts.stream().map(text->new float[1536]).toList(),0);
        });
        var models=mock(CustomerModels.class);
        var props=new CustomerProperties();
        props.setProvider("DASHSCOPE");
        when(models.classify(anyString(),anyString(),any(),any(),any())).thenAnswer(call->{
            String question=call.getArgument(0);
            return write(object().set("intents",array().add(object()
                .put("code",question.equals("settlement query")?"SETTLEMENT":"OTHER")
                .put("confidence",.99).put("questionFragment",question).set("slots",object()))));
        });
        var service=spy(new IntentReleaseService(db,embedding,props,new IntentReleaseEvaluator(models,props)));
        try {
            doReturn(valid()).when(service).snapshot("R1");
            doReturn(object().put("source","L2_RAG").put("code","ORDER_STATUS"),
                object().put("source","L3_LLM").put("code","OTHER"),
                object().put("source","L3_LLM").put("code","OTHER"))
                .when(service).search(any(),any());
            service.build("R1",List.of(
                new IntentReleaseService.Evaluation("order query","ORDER_STATUS"),
                new IntentReleaseService.Evaluation("settlement query","SETTLEMENT"),
                new IntentReleaseService.Evaluation("unrelated query","OTHER")));
            verify(mysql).update(
                eq("UPDATE t_customer_service_intent_release SET status=?,validation_report_json=?,validated_time=now(3) WHERE release_no=? AND status='VALIDATING'"),
                eq("READY"),argThat((String json)->{
                    var report=read(json);
                    return report.path("passed").asBoolean()
                        &&"L2_THEN_L3".equals(report.path("evaluationMode").asText())
                        &&report.path("l2AcceptedCount").asInt()==1
                        &&report.path("l3FallbackCount").asInt()==2
                        &&report.path("correctCount").asInt()==3
                        &&report.path("finalAccuracy").asDouble()==1;
                }),eq("R1"));
            verify(models,times(2)).classify(anyString(),anyString(),any(),any(),any());
        } finally {service.close();}
    }

    @Test void realProviderCannotActivateSnapshotValidatedByMockL3() throws Exception {
        var props=new CustomerProperties();
        props.setProvider("DASHSCOPE");
        var db=mock(CustomerDatabase.class);
        var service=spy(new IntentReleaseService(db,mock(OnlineEmbedding.class),props,mock(IntentReleaseEvaluator.class)));
        try {
            var snapshot=valid();
            snapshot.header().put("status","READY");
            snapshot.header().put("validation_report_json","{\"passed\":true,\"mockL3\":true}");
            doReturn(snapshot).when(service).snapshot("R1");
            assertThrows(com.example.agent.exception.RagApiException.class,()->service.activate("R1"));
            verifyNoInteractions(db);
        } finally {service.close();}
    }
}
