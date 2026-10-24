package com.example.agent.customer;

import com.example.agent.exception.RagApiException;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.TransactionTimedOutException;

import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static com.example.agent.customer.CustomerJson.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real JDBC transactions against isolated H2 fixtures; no remote DB or model calls. */
class IntentReleaseSnapshotTest {
    private static final String EXAMPLE_INSERT="INSERT INTO t_customer_service_intent_release_example(";

    @Test void expandedCatalogSurvivesThreeSecondOnlineLimitAndBuildStartsAfterCommit() throws Exception {
        try(var f=new Fixture()) {
            doAnswer(call->{
                Thread.sleep(3100); // Exhaust the old shared transaction's entire timeout.
                return call.callRealMethod();
            }).when(f.jdbc).queryForList("SELECT * FROM t_customer_service_intent WHERE status='ENABLED' ORDER BY intent_code");

            String no=f.service.create(1,f.tests);
            assertTrue(f.service.started.await(5,TimeUnit.SECONDS));
            assertNull(f.service.failure);
            var snapshot=f.service.observed;
            assertEquals(no,snapshot.no());
            assertEquals(13,snapshot.items().size());
            assertEquals(182,snapshot.count());
            assertEquals("BUILDING",snapshot.header().get("status"));
            assertDoesNotThrow(()->IntentReleaseService.validate(snapshot));
            for(var item:snapshot.items())assertEquals(14,((Number)item.get("expected_example_count")).intValue());
            assertEquals(3,f.db.tx.getTimeout(),"Online transaction timeout must stay unchanged");
            assertEquals(182,f.count("t_customer_service_intent_example"));
            assertEquals("PREVIOUS",f.jdbc.queryForObject("SELECT active_release_no FROM t_customer_service_intent_active_release",String.class));
            verify(f.jdbc,times(2)).update(startsWith(EXAMPLE_INSERT),any(Object[].class));
            verify(f.embedding,never()).embedBatchWithUsage(anyList(),anyString(),anyInt());
        }
    }

    @Test void laterBatchFailureRollsBackHeaderAndAllEarlierSnapshotRows() throws Exception {
        try(var f=new Fixture()) {
            AtomicInteger batches=new AtomicInteger();
            doAnswer(call->{
                if(batches.incrementAndGet()==2)throw new TransactionTimedOutException("injected timeout");
                return call.callRealMethod();
            }).when(f.jdbc).update(startsWith(EXAMPLE_INSERT),any(Object[].class));

            RagApiException error=assertThrows(RagApiException.class,()->f.service.create(1,f.tests));
            assertEquals("INTENT_SNAPSHOT_TIMEOUT",error.errorCode());
            assertEquals(2,batches.get());
            f.assertNoSnapshot();
            assertEquals(182,f.count("t_customer_service_intent_example"));
        }
    }

    @Test void staleSourceVersionAndMissingCoverageStillPreventSnapshotCreation() throws Exception {
        try(var f=new Fixture()) {
            var withoutOther=f.tests.stream().filter(t->!t.expectedIntent().equals("OTHER")).toList();
            assertEquals("INTENT_EVAL_COVERAGE",
                assertThrows(RagApiException.class,()->f.service.create(1,withoutOther)).errorCode());
            f.jdbc.update("UPDATE t_customer_service_intent_example SET intent_version=2 WHERE example_no='ORDER_STATUS-0'");
            assertEquals("INTENT_RELEASE_INCOMPLETE",
                assertThrows(RagApiException.class,()->f.service.create(1,f.tests)).errorCode());
            f.assertNoSnapshot();
        }
    }

    private static class ObservingService extends IntentReleaseService {
        final CountDownLatch started=new CountDownLatch(1);
        volatile Snapshot observed;
        volatile Throwable failure;

        ObservingService(CustomerDatabase db,OnlineEmbedding embedding) throws Exception {
            super(db,embedding,properties(),mock(IntentReleaseEvaluator.class));
        }

        private static CustomerProperties properties() {
            var properties=new CustomerProperties();
            properties.setPreSaleEnabled(false);
            return properties;
        }

        @Override void build(String no,List<Evaluation> tests) {
            try {
                // This worker has its own JDBC connection and can only see committed data.
                observed=snapshot(no);
                validate(observed);
            } catch(Throwable e) {failure=e;}
            finally {started.countDown();}
        }
    }

    private static class Fixture implements AutoCloseable {
        final CustomerDatabase db;
        final JdbcTemplate jdbc;
        final OnlineEmbedding embedding=mock(OnlineEmbedding.class);
        final ObservingService service;
        final List<IntentReleaseService.Evaluation> tests=new ArrayList<>();

        Fixture() throws Exception {
            var source=new JdbcDataSource();
            source.setURL("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
            db=new CustomerDatabase(source,source);
            jdbc=spy(db.mysql);
            ReflectionTestUtils.setField(db,"mysql",jdbc);
            jdbc.execute("""
                CREATE TABLE t_customer_service_intent (
                    intent_code varchar(48) PRIMARY KEY,intent_version int,intent_name varchar(64),
                    description varchar(512),route_node varchar(48),required_slots_json varchar(200),
                    similarity_threshold decimal(5,4),status varchar(16))
                """);
            jdbc.execute("""
                CREATE TABLE t_customer_service_intent_example (
                    example_no varchar(80),example_version int,intent_code varchar(48),intent_version int,
                    example_text varchar(512),normalized_text varchar(512),example_hash char(64),status varchar(16),
                    PRIMARY KEY(example_no,example_version))
                """);
            jdbc.execute("""
                CREATE TABLE t_customer_service_intent_release (
                    release_no varchar(64) PRIMARY KEY,status varchar(16) DEFAULT 'DRAFT',
                    embedding_model varchar(64),embedding_dim int DEFAULT 1536,
                    default_similarity_threshold decimal(5,4) DEFAULT 0.8800,min_margin decimal(5,4) DEFAULT 0.0500,
                    expected_intent_count int DEFAULT 0,expected_example_count int DEFAULT 0,
                    manifest_hash char(64),created_by bigint)
                """);
            jdbc.execute("""
                CREATE TABLE t_customer_service_intent_release_item (
                    release_no varchar(64),intent_code varchar(48),intent_version int,intent_name varchar(64),
                    description varchar(512),route_node varchar(48),required_slots_json varchar(200),
                    similarity_threshold decimal(5,4),expected_example_count int,
                    PRIMARY KEY(release_no,intent_code),
                    FOREIGN KEY(release_no) REFERENCES t_customer_service_intent_release(release_no))
                """);
            jdbc.execute("""
                CREATE TABLE t_customer_service_intent_release_example (
                    release_no varchar(64),example_no varchar(80),example_version int,intent_code varchar(48),
                    intent_version int,example_text varchar(512),normalized_text varchar(512),
                    example_hash char(64),embedding_input_hash char(64),PRIMARY KEY(release_no,example_no),
                    FOREIGN KEY(release_no,intent_code) REFERENCES t_customer_service_intent_release_item(release_no,intent_code))
                """);
            jdbc.execute("CREATE TABLE t_customer_service_intent_active_release(scene varchar(32),active_release_no varchar(64))");
            jdbc.update("INSERT INTO t_customer_service_intent_active_release VALUES('CUSTOMER_SERVICE','PREVIOUS')");
            for(var entry:IntentCatalog.ROUTES.entrySet()) {
                String code=entry.getKey();
                jdbc.update("INSERT INTO t_customer_service_intent VALUES(?,1,?,?,?, ?,NULL,'ENABLED')",
                    code,code,"test description",entry.getValue(),write(IntentCatalog.slots(code)));
                // Extra enabled L1/memory/pre-sale rows must not expand the release scope.
                for(int i=0;i<14;i++) {
                    String text=code+" question "+i,normalized=normalize(text);
                    jdbc.update("INSERT INTO t_customer_service_intent_example VALUES(?,1,?,1,?,?,?,'ENABLED')",
                        code+"-"+i,code,text,normalized,hash(normalized));
                }
                if(IntentCatalog.l2(code)&&!code.startsWith("PRE_SALE"))
                    tests.add(new IntentReleaseService.Evaluation("independent "+code,code));
            }
            // Keep only selected sources for the 182-row source-count assertions.
            jdbc.update("DELETE FROM t_customer_service_intent_example WHERE intent_code IN ('GREETING','BOT_IDENTITY','OTHER','MEMORY_QUERY','MEMORY_FORGET','PRE_SALE_PRODUCT','PRE_SALE_PROCESS','PRE_SALE_LIMIT')");
            tests.add(new IntentReleaseService.Evaluation("unrelated topic","OTHER"));
            when(embedding.model()).thenReturn("model-a");
            service=new ObservingService(db,embedding);
        }

        int count(String table) {return jdbc.queryForObject("SELECT COUNT(*) FROM "+table,Integer.class);}

        void assertNoSnapshot() {
            assertEquals(0,count("t_customer_service_intent_release"));
            assertEquals(0,count("t_customer_service_intent_release_item"));
            assertEquals(0,count("t_customer_service_intent_release_example"));
            assertEquals(1,service.started.getCount(),"No build may be queued after rollback");
        }

        @Override public void close() {
            service.close();
            jdbc.execute("SHUTDOWN");
        }
    }
}
