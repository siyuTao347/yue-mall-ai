package com.example.agent.customer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import com.example.agent.rag.embed.VectorFormat;
import jakarta.annotation.PreDestroy;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionTimedOutException;
import org.springframework.transaction.support.TransactionTemplate;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestClientResponseException;
import static com.example.agent.customer.CustomerJson.*;

@Service
public class IntentReleaseService {
    private static final Logger log=LoggerFactory.getLogger(IntentReleaseService.class);
    private static final int SNAPSHOT_BATCH_SIZE=100;
    public record Evaluation(String query,String expectedIntent) {}
    public record Snapshot(Map<String,Object> header,List<Map<String,Object>> items,List<Map<String,Object>> examples) {
        String no(){return header.get("release_no").toString();}
        String model(){return header.get("embedding_model").toString();}
        int count(){return ((Number)header.get("expected_example_count")).intValue();}
    }
    private final CustomerDatabase db;
    private final OnlineEmbedding embedding;
    private final CustomerProperties properties;
    private final IntentReleaseEvaluator evaluator;
    private final String query;
    private final String fastQuery;
    private record ActivePointer(String releaseNo,long expiresAtNanos) {}
    private volatile ActivePointer activePointer;
    private final Map<String,Snapshot> verifiedSnapshots=Collections.synchronizedMap(new LinkedHashMap<>(8,.75f,true));
    private final ExecutorService builder=new ThreadPoolExecutor(1,1,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(4),r->{Thread t=new Thread(r,"intent-release-builder");t.setDaemon(true);return t;});
    public IntentReleaseService(CustomerDatabase db,OnlineEmbedding embedding,CustomerProperties properties,IntentReleaseEvaluator evaluator) throws Exception {
        this.db=db;this.embedding=embedding;this.properties=properties;this.evaluator=evaluator;
        query=new ClassPathResource("customer/intent-search.sql").getContentAsString(StandardCharsets.UTF_8);
        fastQuery=new ClassPathResource("customer/intent-search-fast.sql").getContentAsString(StandardCharsets.UTF_8);
    }
    @PreDestroy public void close(){builder.shutdownNow();}
    public List<Map<String,Object>> list(){return db.mysql.queryForList("SELECT release_no,status,embedding_model,expected_intent_count,expected_example_count,validated_time,created_time FROM t_customer_service_intent_release ORDER BY created_time DESC LIMIT 50");}
    public Map<String,Object> detail(String no){Map<String,Object> h=header(no);h.put("report",read(h.remove("validation_report_json")));return h;}
    private Map<String,Object> header(String no){var rows=db.mysql.queryForList("SELECT * FROM t_customer_service_intent_release WHERE release_no=?",no);if(rows.isEmpty())throw error(404,"INTENT_RELEASE_NOT_FOUND","版本不存在");return rows.getFirst();}
    Snapshot snapshot(String no){return new Snapshot(immutable(header(no)),immutableRows(db.mysql.queryForList("SELECT intent_code,intent_version,intent_name,description,route_node,required_slots_json,similarity_threshold,expected_example_count FROM t_customer_service_intent_release_item WHERE release_no=? ORDER BY intent_code",no)),
        immutableRows(db.mysql.queryForList("SELECT example_no,example_version,intent_code,intent_version,example_text,normalized_text,example_hash,embedding_input_hash FROM t_customer_service_intent_release_example WHERE release_no=? ORDER BY example_no",no)));}
    private static Map<String,Object> immutable(Map<String,Object> row){return Collections.unmodifiableMap(new LinkedHashMap<>(row));}
    private static List<Map<String,Object>> immutableRows(List<Map<String,Object>> rows){return rows.stream().map(IntentReleaseService::immutable).toList();}
    private Snapshot verifiedSnapshot(String no) {
        synchronized(verifiedSnapshots) {
            Snapshot cached=verifiedSnapshots.get(no);if(cached!=null)return cached;
            Snapshot value=snapshot(no);assertReady(value);validate(value);verifiedSnapshots.put(no,value);
            while(verifiedSnapshots.size()>3)verifiedSnapshots.remove(verifiedSnapshots.keySet().iterator().next());
            return value;
        }
    }
    static String canonical(Object value){
        if(value instanceof Map<?,?> m){Map<String,String> sorted=new TreeMap<>();m.forEach((k,v)->sorted.put(k.toString(),canonical(v)));return write(sorted);}
        if(value instanceof List<?> l)return write(l.stream().map(IntentReleaseService::canonical).toList());
        if(value instanceof Number n)return new BigDecimal(n.toString()).stripTrailingZeros().toPlainString();
        return value==null?"null":value.toString();
    }
    static String manifest(Snapshot s){return hash(canonical(Map.of("model",s.model(),"dim",s.header.get("embedding_dim"),"threshold",s.header.get("default_similarity_threshold"),"margin",s.header.get("min_margin"),"intents",s.items,"examples",s.examples)));}
    static void validate(Snapshot s){
        if(s.items.size()<2 || s.items.size()!=((Number)s.header.get("expected_intent_count")).intValue() || s.examples.size()!=s.count())throw incomplete();
        if(((Number)s.header.get("embedding_dim")).intValue()!=1536)throw incomplete();
        double margin=((Number)s.header.get("min_margin")).doubleValue();if(!Double.isFinite(margin)||margin<0||margin>2)throw incomplete();
        Set<String> ids=new HashSet<>();Map<String,Integer> counts=new HashMap<>();Map<String,Map<String,Object>> items=new HashMap<>();
        for(var item:s.items){String code=item.get("intent_code").toString();if(!IntentCatalog.l2(code)||!IntentCatalog.ROUTES.get(code).equals(item.get("route_node")))throw incomplete();
            double t=((Number)item.get("similarity_threshold")).doubleValue();if(!Double.isFinite(t)||t<0||t>1||items.put(code,item)!=null)throw incomplete();}
        for(var e:s.examples){String code=e.get("intent_code").toString();var i=items.get(code);String normalized=e.get("normalized_text").toString();
            if(!ids.add(e.get("example_no").toString())||i==null||!canonical(i.get("intent_version")).equals(canonical(e.get("intent_version")))
                ||!normalize(e.get("example_text").toString()).equals(normalized)||!hash(normalized).equals(e.get("example_hash"))
                ||!hash(s.model()+"|"+normalized).equals(e.get("embedding_input_hash")))throw incomplete();counts.merge(code,1,Integer::sum);}
        for(var item:s.items)if(counts.getOrDefault(item.get("intent_code"),0)<1||counts.get(item.get("intent_code"))!=((Number)item.get("expected_example_count")).intValue())throw incomplete();
        if(!manifest(s).equals(s.header.get("manifest_hash")))throw incomplete();
    }
    static com.example.agent.exception.RagApiException incomplete(){return error(503,"INTENT_RELEASE_INCOMPLETE","意图库版本不完整");}
    public ObjectNode recognize(String question,CustomerBudget run){
        if(!properties.isRagEnabled())return fallback("INTENT_RAG_DISABLED");
        // Mock classification uses explicit fixtures in Master; hash embeddings never establish semantic confidence.
        if("MOCK".equals(properties.getProvider()))return fallback("MOCK_CLASSIFICATION");
        long started=System.nanoTime();ObjectNode trace=object();
        try {
            CustomerBudget budget=new CustomerBudget(Math.min(run.remaining(),properties.getRetrievalTimeoutMs()));
            String active=budget.call(500,this::activeRelease);
            if(active==null)throw error(503,"INTENT_RELEASE_UNPUBLISHED","意图库尚未发布");
            Snapshot snapshot=budget.call(1000,()->verifiedSnapshot(active));
            trace.put("releaseNo",snapshot.no()).put("manifestHash",snapshot.header.get("manifest_hash").toString())
                .put("expectedExampleCount",snapshot.count()).put("embeddingModel",snapshot.model()).put("embeddingDim",1536);
            OnlineEmbedding.Result embedded=budget.call(9000,()->embedding.embedWithUsage(normalize(question),snapshot.model(),1536));
            trace.set("embeddingTokens",node(embedded.tokens()));trace.put("embeddingCacheHit",embedded.cached());
            ObjectNode result=budget.call(2000,()->searchFast(snapshot,embedded.vector()));
            result.set("embeddingTokens",node(embedded.tokens()));
            if(!budget.call(500,()->Objects.equals(activeRelease(),snapshot.no())))throw incomplete();
            result.put("elapsedMs",(System.nanoTime()-started)/1000000);return result;
        }catch(Exception e){trace.put("source","L3_LLM").put("fallbackReason",e instanceof com.example.agent.exception.RagApiException r?r.errorCode():"INTENT_RAG_UNAVAILABLE");
            trace.put("elapsedMs",(System.nanoTime()-started)/1000000);return trace;}
    }
    private String activeRelease() {
        long now=System.nanoTime();ActivePointer cached=activePointer;
        if(cached!=null&&cached.expiresAtNanos()>now)return cached.releaseNo();
        synchronized(this) {
            now=System.nanoTime();cached=activePointer;
            if(cached!=null&&cached.expiresAtNanos()>now)return cached.releaseNo();
        var pointer=db.mysql.queryForList("SELECT active_release_no FROM t_customer_service_intent_active_release WHERE scene='CUSTOMER_SERVICE'");
            String release=pointer.isEmpty()?null:Objects.toString(pointer.getFirst().get("active_release_no"),null);
            activePointer=new ActivePointer(release,System.nanoTime()+TimeUnit.SECONDS.toNanos(2));return release;
        }
    }
    public boolean isActive(String no){return no!=null&&!no.isBlank()&&Objects.equals(activeRelease(),no);}

    private void assertReady(Snapshot s){if(!"READY".equals(s.header.get("status")))throw incomplete();if(!"MOCK".equals(properties.getProvider())&&(embedding.mock()||read(s.header.get("validation_report_json")).path("mockL3").asBoolean()))throw incomplete();}
    ObjectNode search(Snapshot s,float[] vector){
        Map<String,Object> params=new HashMap<>();params.put("expected_examples_json",write(s.examples));params.put("query_vector",VectorFormat.toLiteral(vector));
        params.put("release_no",s.no());params.put("embedding_model",s.model());params.put("expected_example_count",s.count());params.put("expected_intent_count",s.items.size());params.put("intent_top_k",5);
        Map<String,Object> row=new NamedParameterJdbcTemplate(db.vector).queryForMap(query,params);
        ObjectNode result=object().put("releaseNo",s.no()).put("manifestHash",s.header.get("manifest_hash").toString()).put("expectedExampleCount",s.count()).put("embeddingModel",s.model()).put("embeddingDim",1536);
        for(String k:List.of("actual_count","matched_count","matched_intent_count"))result.set(k,node(row.get(k)));
        var candidates=read(row.get("candidates"));result.set("candidates",candidates);
        if(((Number)row.get("actual_count")).intValue()!=s.count()||((Number)row.get("matched_count")).intValue()!=s.count()||((Number)row.get("matched_intent_count")).intValue()!=s.items.size()||candidates.size()<2)throw incomplete();
        String code=candidates.get(0).path("intent_code").asText();double score=candidates.get(0).path("score").asDouble();double second=candidates.get(1).path("score").asDouble();
        var item=s.items.stream().filter(i->code.equals(i.get("intent_code"))).findFirst().orElseThrow();double threshold=((Number)item.get("similarity_threshold")).doubleValue(),margin=((Number)s.header.get("min_margin")).doubleValue();
        boolean accept=accept(score,second,threshold,margin);
        result.put("source",accept?"L2_RAG":"L3_LLM").put("code",accept?code:"OTHER").put("similarity",score).put("runnerUpScore",second).put("threshold",threshold).put("minMargin",margin);
        result.put("matchedExampleCount",((Number)row.get("matched_count")).intValue());result.set("matched",candidates.get(0));result.put("fallbackReason",accept?null:"INTENT_LOW_SCORE_OR_MARGIN");
        result.set("requiredSlots",read(item.get("required_slots_json")));return result;
    }
    ObjectNode searchFast(Snapshot s,float[] vector) {
        Map<String,Object> params=Map.of("query_vector",VectorFormat.toLiteral(vector),"release_no",s.no(),"embedding_model",s.model(),"intent_top_k",5);
        Map<String,Object> row=new NamedParameterJdbcTemplate(db.vector).queryForMap(fastQuery,params);
        JsonNode candidates=read(row.get("candidates"));if(!candidates.isArray()||candidates.size()<2)throw incomplete();
        ObjectNode result=object().put("releaseNo",s.no()).put("manifestHash",s.header.get("manifest_hash").toString())
            .put("expectedExampleCount",s.count()).put("matchedExampleCount",s.count()).put("embeddingModel",s.model()).put("embeddingDim",1536);
        result.set("candidates",candidates);String code=candidates.get(0).path("intent_code").asText();
        double score=candidates.get(0).path("score").asDouble(),second=candidates.get(1).path("score").asDouble();
        Map<String,Object> item=s.items.stream().filter(i->code.equals(i.get("intent_code"))).findFirst().orElseThrow(IntentReleaseService::incomplete);
        double threshold=((Number)item.get("similarity_threshold")).doubleValue(),margin=((Number)s.header.get("min_margin")).doubleValue();
        boolean accept=accept(score,second,threshold,margin);
        result.put("source",accept?"L2_RAG":"L3_LLM").put("code",accept?code:"OTHER").put("similarity",score)
            .put("runnerUpScore",second).put("threshold",threshold).put("minMargin",margin);
        result.put("fallbackReason",accept?null:"INTENT_LOW_SCORE_OR_MARGIN");result.set("matched",candidates.get(0));
        result.set("requiredSlots",read(item.get("required_slots_json")));return result;
    }
    static boolean accept(double best,double second,double threshold,double margin){return Double.isFinite(best)&&Double.isFinite(second)&&best>threshold&&BigDecimal.valueOf(best).subtract(BigDecimal.valueOf(second)).compareTo(BigDecimal.valueOf(margin))>=0;}
    static ObjectNode fallback(String reason){return object().put("source","L3_LLM").put("fallbackReason",reason);}
    public String create(long operator,List<Evaluation> tests){
        if(tests==null || tests.size()<3 || tests.size()>200)throw error(400,"INTENT_EVAL_REQUIRED","请提供 3–200 条独立评估问句，覆盖全部意图及 OTHER 负例");
        for(var t:tests)if(t.query()==null||t.query().isBlank()||t.query().length()>512||!IntentCatalog.ROUTES.containsKey(t.expectedIntent()))throw error(400,"INTENT_EVAL_INVALID","评估问句或意图不正确");
        String no=id("IR");String model=embedding.model();
        // Freezing a complete catalog is an admin operation, not a three-second online query.
        // Copy the transaction definition so the shared online timeout remains unchanged.
        TransactionTemplate snapshotTx=new TransactionTemplate(db.tx.getTransactionManager(),db.tx);
        snapshotTx.setTimeout(30);
        try { snapshotTx.executeWithoutResult(status->{
            List<Map<String,Object>> items=db.mysql.queryForList("SELECT * FROM t_customer_service_intent WHERE status='ENABLED' ORDER BY intent_code");
            items.removeIf(i->!IntentCatalog.l2(i.get("intent_code").toString())||(!properties.isPreSaleEnabled()&&i.get("intent_code").toString().startsWith("PRE_SALE")));
            Set<String> coverage=new HashSet<>();tests.forEach(t->coverage.add(t.expectedIntent()));
            if(items.size()<2||!coverage.contains("OTHER")||items.stream().anyMatch(i->!coverage.contains(i.get("intent_code"))))throw error(400,"INTENT_EVAL_COVERAGE","评估集必须覆盖全部待发布意图及 OTHER 负例");
            var sourceExamples=new NamedParameterJdbcTemplate(db.mysql).queryForList(
                "SELECT * FROM t_customer_service_intent_example WHERE status='ENABLED' AND intent_code IN (:codes) ORDER BY intent_code,example_no",
                Map.of("codes",items.stream().map(i->i.get("intent_code")).toList()));
            Map<String,List<Map<String,Object>>> byIntent=new HashMap<>();
            for(var e:sourceExamples)byIntent.computeIfAbsent(e.get("intent_code").toString(),ignored->new ArrayList<>()).add(e);
            List<Object[]> itemRows=new ArrayList<>(),exampleRows=new ArrayList<>();
            for(var i:items){String code=i.get("intent_code").toString();var examples=byIntent.getOrDefault(code,List.of());
                if(examples.isEmpty()||examples.stream().anyMatch(e->!canonical(e.get("intent_version")).equals(canonical(i.get("intent_version")))))throw incomplete();
                Object threshold=i.get("similarity_threshold")==null?new BigDecimal("0.8800"):i.get("similarity_threshold");
                itemRows.add(new Object[]{no,code,i.get("intent_version"),i.get("intent_name"),i.get("description"),i.get("route_node"),i.get("required_slots_json"),threshold,examples.size()});
                for(var e:examples){String normalized=normalize(e.get("example_text").toString());
                    if(!normalized.equals(e.get("normalized_text"))||!hash(normalized).equals(e.get("example_hash")))throw incomplete();
                    exampleRows.add(new Object[]{no,e.get("example_no"),e.get("example_version"),code,e.get("intent_version"),e.get("example_text"),normalized,hash(normalized),hash(model+"|"+normalized)});
                }
            }
            db.mysql.update("INSERT INTO t_customer_service_intent_release(release_no,embedding_model,created_by) VALUES(?,?,?)",no,model,operator);
            insertSnapshotRows("INSERT INTO t_customer_service_intent_release_item(release_no,intent_code,intent_version,intent_name,description,route_node,required_slots_json,similarity_threshold,expected_example_count) VALUES ",itemRows);
            insertSnapshotRows("INSERT INTO t_customer_service_intent_release_example(release_no,example_no,example_version,intent_code,intent_version,example_text,normalized_text,example_hash,embedding_input_hash) VALUES ",exampleRows);
            db.mysql.update("UPDATE t_customer_service_intent_release SET expected_intent_count=?,expected_example_count=? WHERE release_no=?",items.size(),exampleRows.size(),no);
            String digest=manifest(snapshot(no));db.mysql.update("UPDATE t_customer_service_intent_release SET manifest_hash=?,status='BUILDING' WHERE release_no=?",digest,no);
        }); } catch(TransactionTimedOutException e) {
            log.warn("Intent release snapshot transaction timed out: releaseNo={}",no);
            throw error(504,"INTENT_SNAPSHOT_TIMEOUT","保存意图库发布快照超时，已回滚；请检查 MySQL 连接后重试");
        }
        try{builder.submit(()->build(no,tests));}catch(RejectedExecutionException e){db.mysql.update("UPDATE t_customer_service_intent_release SET status='FAILED' WHERE release_no=?",no);throw error(503,"INTENT_BUILD_BUSY","构建队列已满");}return no;
    }
    private void insertSnapshotRows(String insert,List<Object[]> rows) {
        // Use multi-row INSERT explicitly: JDBC batch rewriting is not enabled on every MySQL URL.
        for(int from=0;from<rows.size();from+=SNAPSHOT_BATCH_SIZE) {
            List<Object[]> batch=rows.subList(from,Math.min(from+SNAPSHOT_BATCH_SIZE,rows.size()));
            db.mysql.update(insert+String.join(",",Collections.nCopies(batch.size(),"(?,?,?,?,?,?,?,?,?)")),
                batch.stream().flatMap(Arrays::stream).toArray());
        }
    }
    void build(String no,List<Evaluation> tests){
        String stage="SNAPSHOT_VALIDATION";
        try{
            Snapshot s=snapshot(no);validate(s);
            stage="EVALUATION_INPUT";
            Set<String> training=new HashSet<>();s.examples.forEach(e->training.add(e.get("normalized_text").toString()));
            List<String> normalizedTests=tests.stream().map(test->normalize(test.query())).toList();
            for(String normalized:normalizedTests)if(training.contains(normalized))throw error(400,"INTENT_EVAL_LEAKAGE","评估问句不能与训练示例完全相同");
            String insert="INSERT INTO rag_customer_service_intent_release_embedding(release_no,example_no,example_version,intent_code,intent_version,embedding,embedding_model,embedding_dim,embedding_input_hash) VALUES(?,?,?,?,?,?::vector,?,1536,?) ON CONFLICT(release_no,example_no) DO UPDATE SET embedding=EXCLUDED.embedding,embedding_input_hash=EXCLUDED.embedding_input_hash,updated_time=now()";
            for(int from=0;from<s.examples.size();from+=32){
                List<Map<String,Object>> examples=s.examples.subList(from,Math.min(from+32,s.examples.size()));
                stage="EMBEDDING_EXAMPLES";
                var vectors=embedding.embedBatchWithUsage(examples.stream().map(e->e.get("normalized_text").toString()).toList(),s.model(),1536).vectors();
                List<Object[]> rows=new ArrayList<>(examples.size());
                for(int i=0;i<examples.size();i++){var e=examples.get(i);rows.add(new Object[]{no,e.get("example_no"),e.get("example_version"),e.get("intent_code"),e.get("intent_version"),VectorFormat.toLiteral(vectors.get(i)),s.model(),e.get("embedding_input_hash")});}
                stage="WRITING_VECTORS";
                db.tx.executeWithoutResult(tx->{var h=db.mysql.queryForMap("SELECT status FROM t_customer_service_intent_release WHERE release_no=? FOR UPDATE",no);if(!"BUILDING".equals(h.get("status")))throw incomplete();db.vector.batchUpdate(insert,rows);});
            }
            stage="STARTING_VALIDATION";
            if(db.mysql.update("UPDATE t_customer_service_intent_release SET status='VALIDATING' WHERE release_no=? AND status='BUILDING'",no)!=1)throw incomplete();
            ArrayNode results=array();
            List<float[]> testVectors=new ArrayList<>(tests.size());
            stage="EMBEDDING_EVALUATIONS";
            for(int from=0;from<normalizedTests.size();from+=32)testVectors.addAll(embedding.embedBatchWithUsage(normalizedTests.subList(from,Math.min(from+32,normalizedTests.size())),s.model(),1536).vectors());
            stage="EVALUATING";
            for(int index=0;index<tests.size();index++){var test=tests.get(index);
                if(Thread.currentThread().isInterrupted())throw error(504,"AGENT_CANCELLED","发布校验已中断");
                ObjectNode actual=search(s,testVectors.get(index));
                results.add(evaluator.evaluate(test,actual));}
            ObjectNode report=evaluator.summarize(results).put("mockEmbedding",embedding.mock()).put("manifestHash",manifest(s)).put("evaluationHash",hash(write(tests))).put("expectedExampleCount",s.count()).put("embeddingModel",s.model()).put("embeddingDim",1536);report.set("evaluations",results);
            boolean passed=report.path("passed").asBoolean();
            report.put("stage","EVALUATING");
            if(!passed)report.put("errorCode","INTENT_EVALUATION_FAILED").put("error","L2→L3 发布校验存在最终分类不匹配或调用异常，请查看 evaluations");
            stage="SAVING_REPORT";
            db.mysql.update("UPDATE t_customer_service_intent_release SET status=?,validation_report_json=?,validated_time=now(3) WHERE release_no=? AND status='VALIDATING'",passed?"READY":"FAILED",write(report),no);
        }catch(Exception e){
            ObjectNode report=buildFailure(stage,e);
            log.error("Intent release build failed: releaseNo={}, report={}",no,write(report));
            db.mysql.update("UPDATE t_customer_service_intent_release SET status='FAILED',validation_report_json=? WHERE release_no=? AND status IN ('BUILDING','VALIDATING')",write(report),no);
        }
    }

    static ObjectNode buildFailure(String stage,Exception exception) {
        ObjectNode report=object().put("passed",false).put("stage",stage)
            .put("errorCode","INTENT_BUILD_FAILED").put("error","构建失败，请按 stage 和异常类型检查配置及依赖")
            .put("exceptionType",exception.getClass().getSimpleName());
        Set<Throwable> seen=Collections.newSetFromMap(new IdentityHashMap<>());
        for(Throwable cause=exception;cause!=null&&seen.add(cause);cause=cause.getCause()) {
            report.put("causeType",cause.getClass().getSimpleName());
            if(cause instanceof com.example.agent.exception.RagApiException r) {
                report.put("errorCode",r.errorCode()).put("error",diagnosticText(r.getMessage()));
            } else if(cause instanceof RestClientResponseException http) {
                report.put("errorCode","INTENT_EMBEDDING_HTTP_ERROR").put("httpStatus",http.getStatusCode().value())
                    .put("error","Embedding 服务拒绝请求，请检查模型权限、维度和单次批量大小");
                // Never retain the full response body, headers, request or credentials.
                try {
                    String body=http.getResponseBodyAsString();
                    if(body.length()<=16384) {
                        JsonNode root=M.readTree(body);
                        if(root!=null) {
                            JsonNode detail=root.path("error").isObject()?root.path("error"):root;
                            for(String key:List.of("code","message"))
                                if(detail.path(key).isTextual())report.put("provider"+(key.equals("code")?"Code":"Message"),diagnosticText(detail.path(key).asText()));
                        }
                    }
                } catch(Exception ignored) {}
            } else if(cause instanceof java.net.SocketTimeoutException||cause instanceof java.net.http.HttpTimeoutException) {
                report.put("errorCode","INTENT_DEPENDENCY_TIMEOUT").put("error","依赖连接或读取超时，请检查网络和服务超时配置");
            } else if(cause instanceof java.net.ConnectException||cause instanceof java.net.UnknownHostException) {
                report.put("errorCode","INTENT_DEPENDENCY_UNAVAILABLE").put("error","无法连接依赖服务，请检查 DNS、代理与网络");
            } else if(cause instanceof com.example.agent.rag.embed.EmbeddingException&&cause.getCause()==null) {
                report.put("errorCode","INTENT_EMBEDDING_INVALID").put("error",diagnosticText(cause.getMessage()));
            } else if(cause instanceof java.sql.SQLException sql) {
                report.put("errorCode","INTENT_DATABASE_ERROR").put("sqlState",sql.getSQLState())
                    .put("error","数据库操作失败，请按 stage 和 sqlState 检查表结构、权限与连接");
            }
        }
        return report;
    }

    private static String diagnosticText(String text) {
        if(text==null)return "";
        String safe=redact(text)
            .replaceAll("(?i)([\"']?(?:api[_-]?key|password|token|secret)[\"']?\\s*[:=]\\s*)(?:\"[^\"]*\"|'[^']*'|[^\\s,;]+)","$1[已隐藏]")
            .replaceAll("(?i)\\bsk-[a-z0-9_-]+","[已隐藏]")
            .replaceAll("\\beyJ[a-zA-Z0-9_-]+\\.[a-zA-Z0-9_-]+\\.[a-zA-Z0-9_-]+","[已隐藏]")
            .replaceAll("\\s+"," ").strip();
        return safe.substring(0,Math.min(safe.length(),500));
    }
    public void activate(String no){Snapshot s=snapshot(no);assertReady(s);validate(s);
        JsonNode report=read(s.header.get("validation_report_json"));if(!report.path("passed").asBoolean() || ((report.path("mockEmbedding").asBoolean()||report.path("mockL3").asBoolean())&&!"MOCK".equals(properties.getProvider())))throw incomplete();
        search(s,embedding.embed(s.examples.getFirst().get("normalized_text").toString(),s.model(),1536));
        db.tx.executeWithoutResult(tx->{db.mysql.queryForList("SELECT scene FROM t_customer_service_intent_active_release WHERE scene='CUSTOMER_SERVICE' FOR UPDATE");
            var h=db.mysql.queryForMap("SELECT status,manifest_hash FROM t_customer_service_intent_release WHERE release_no=? FOR UPDATE",no);
            if(!"READY".equals(h.get("status"))||!Objects.equals(h.get("manifest_hash"),s.header.get("manifest_hash")))throw incomplete();
            if(db.mysql.update("UPDATE t_customer_service_intent_active_release SET active_release_no=?,lock_version=lock_version+1 WHERE scene='CUSTOMER_SERVICE'",no)!=1)throw incomplete();});
        synchronized(verifiedSnapshots){verifiedSnapshots.put(no,s);while(verifiedSnapshots.size()>3)verifiedSnapshots.remove(verifiedSnapshots.keySet().iterator().next());}
        activePointer=null;
    }
    public void revoke(String no){db.tx.executeWithoutResult(tx->{db.mysql.queryForList("SELECT scene FROM t_customer_service_intent_active_release WHERE scene='CUSTOMER_SERVICE' FOR UPDATE");
        db.mysql.update("UPDATE t_customer_service_intent_release SET status='REVOKED' WHERE release_no=?",no);
            db.mysql.update("UPDATE t_customer_service_intent_active_release SET active_release_no=NULL,lock_version=lock_version+1 WHERE scene='CUSTOMER_SERVICE' AND active_release_no=?",no);});verifiedSnapshots.remove(no);activePointer=null;}
    public boolean ready(String no){return "READY".equals(header(no).get("status"));}
}
