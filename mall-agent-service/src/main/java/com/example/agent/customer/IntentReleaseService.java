package com.example.agent.customer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import com.example.agent.rag.embed.VectorFormat;
import jakarta.annotation.PreDestroy;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import static com.example.agent.customer.CustomerJson.*;

@Service
public class IntentReleaseService {
    public record Evaluation(String query,String expectedIntent) {}
    public record Snapshot(Map<String,Object> header,List<Map<String,Object>> items,List<Map<String,Object>> examples) {
        String no(){return header.get("release_no").toString();}
        String model(){return header.get("embedding_model").toString();}
        int count(){return ((Number)header.get("expected_example_count")).intValue();}
    }
    private final CustomerDatabase db;
    private final OnlineEmbedding embedding;
    private final CustomerProperties properties;
    private final String query;
    private final String fastQuery;
    private record ActivePointer(String releaseNo,long expiresAtNanos) {}
    private volatile ActivePointer activePointer;
    private final Map<String,Snapshot> verifiedSnapshots=Collections.synchronizedMap(new LinkedHashMap<>(8,.75f,true));
    private final ExecutorService builder=new ThreadPoolExecutor(1,1,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(4),r->{Thread t=new Thread(r,"intent-release-builder");t.setDaemon(true);return t;});
    public IntentReleaseService(CustomerDatabase db,OnlineEmbedding embedding,CustomerProperties properties) throws Exception {
        this.db=db;this.embedding=embedding;this.properties=properties;
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

    private void assertReady(Snapshot s){if(!"READY".equals(s.header.get("status")))throw incomplete();if(embedding.mock()&&!"MOCK".equals(properties.getProvider()))throw incomplete();}
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
        db.tx.executeWithoutResult(status->{
            List<Map<String,Object>> items=db.mysql.queryForList("SELECT * FROM t_customer_service_intent WHERE status='ENABLED' ORDER BY intent_code");
            items.removeIf(i->!IntentCatalog.l2(i.get("intent_code").toString())||(!properties.isPreSaleEnabled()&&i.get("intent_code").toString().startsWith("PRE_SALE")));
            Set<String> coverage=new HashSet<>();tests.forEach(t->coverage.add(t.expectedIntent()));
            if(items.size()<2||!coverage.contains("OTHER")||items.stream().anyMatch(i->!coverage.contains(i.get("intent_code"))))throw error(400,"INTENT_EVAL_COVERAGE","评估集必须覆盖全部待发布意图及 OTHER 负例");
            db.mysql.update("INSERT INTO t_customer_service_intent_release(release_no,embedding_model,created_by) VALUES(?,?,?)",no,model,operator);
            int total=0;
            for(var i:items){String code=i.get("intent_code").toString();var examples=db.mysql.queryForList("SELECT * FROM t_customer_service_intent_example WHERE intent_code=? AND status='ENABLED' ORDER BY example_no",code);
                if(examples.isEmpty()||examples.stream().anyMatch(e->!canonical(e.get("intent_version")).equals(canonical(i.get("intent_version")))))throw incomplete();
                Object threshold=i.get("similarity_threshold")==null?new BigDecimal("0.8800"):i.get("similarity_threshold");
                db.mysql.update("INSERT INTO t_customer_service_intent_release_item VALUES(?,?,?,?,?,?,?,?,?)",no,code,i.get("intent_version"),i.get("intent_name"),i.get("description"),i.get("route_node"),i.get("required_slots_json"),threshold,examples.size());
                for(var e:examples){String normalized=normalize(e.get("example_text").toString());
                    if(!normalized.equals(e.get("normalized_text"))||!hash(normalized).equals(e.get("example_hash")))throw incomplete();
                    db.mysql.update("INSERT INTO t_customer_service_intent_release_example VALUES(?,?,?,?,?,?,?,?,?)",no,e.get("example_no"),e.get("example_version"),code,e.get("intent_version"),e.get("example_text"),normalized,hash(normalized),hash(model+"|"+normalized));total++;}
            }
            db.mysql.update("UPDATE t_customer_service_intent_release SET expected_intent_count=?,expected_example_count=? WHERE release_no=?",items.size(),total,no);
            String digest=manifest(snapshot(no));db.mysql.update("UPDATE t_customer_service_intent_release SET manifest_hash=?,status='BUILDING' WHERE release_no=?",digest,no);
        });
        try{builder.submit(()->build(no,tests));}catch(RejectedExecutionException e){db.mysql.update("UPDATE t_customer_service_intent_release SET status='FAILED' WHERE release_no=?",no);throw error(503,"INTENT_BUILD_BUSY","构建队列已满");}return no;
    }
    private void build(String no,List<Evaluation> tests){
        try{
            Snapshot s=snapshot(no);validate(s);
            String insert="INSERT INTO rag_customer_service_intent_release_embedding(release_no,example_no,example_version,intent_code,intent_version,embedding,embedding_model,embedding_dim,embedding_input_hash) VALUES(?,?,?,?,?,?::vector,?,1536,?) ON CONFLICT(release_no,example_no) DO UPDATE SET embedding=EXCLUDED.embedding,embedding_input_hash=EXCLUDED.embedding_input_hash,updated_time=now()";
            for(int from=0;from<s.examples.size();from+=32){
                List<Map<String,Object>> examples=s.examples.subList(from,Math.min(from+32,s.examples.size()));
                var vectors=embedding.embedBatchWithUsage(examples.stream().map(e->e.get("normalized_text").toString()).toList(),s.model(),1536).vectors();
                List<Object[]> rows=new ArrayList<>(examples.size());
                for(int i=0;i<examples.size();i++){var e=examples.get(i);rows.add(new Object[]{no,e.get("example_no"),e.get("example_version"),e.get("intent_code"),e.get("intent_version"),VectorFormat.toLiteral(vectors.get(i)),s.model(),e.get("embedding_input_hash")});}
                db.tx.executeWithoutResult(tx->{var h=db.mysql.queryForMap("SELECT status FROM t_customer_service_intent_release WHERE release_no=? FOR UPDATE",no);if(!"BUILDING".equals(h.get("status")))throw incomplete();db.vector.batchUpdate(insert,rows);});
            }
            if(db.mysql.update("UPDATE t_customer_service_intent_release SET status='VALIDATING' WHERE release_no=? AND status='BUILDING'",no)!=1)throw incomplete();
            ArrayNode results=array();boolean passed=true;Set<String> training=new HashSet<>();s.examples.forEach(e->training.add(e.get("normalized_text").toString()));
            List<String> normalizedTests=tests.stream().map(test->normalize(test.query())).toList();
            for(String normalized:normalizedTests)if(training.contains(normalized))throw error(400,"INTENT_EVAL_LEAKAGE","评估问句不能与训练示例完全相同");
            List<float[]> testVectors=new ArrayList<>(tests.size());
            for(int from=0;from<normalizedTests.size();from+=32)testVectors.addAll(embedding.embedBatchWithUsage(normalizedTests.subList(from,Math.min(from+32,normalizedTests.size())),s.model(),1536).vectors());
            for(int index=0;index<tests.size();index++){var test=tests.get(index);
                ObjectNode actual=search(s,testVectors.get(index));boolean ok=test.expectedIntent().equals(actual.path("code").asText());passed &= ok;
                results.add(object().put("query",redact(test.query())).put("expected",test.expectedIntent()).put("actual",actual.path("code").asText()).put("passed",ok));}
            ObjectNode report=object().put("passed",passed).put("mockEmbedding",embedding.mock()).put("manifestHash",manifest(s)).put("evaluationHash",hash(write(tests))).put("expectedExampleCount",s.count()).put("embeddingModel",s.model()).put("embeddingDim",1536);report.set("evaluations",results);
            db.mysql.update("UPDATE t_customer_service_intent_release SET status=?,validation_report_json=?,validated_time=now(3) WHERE release_no=? AND status='VALIDATING'",passed?"READY":"FAILED",write(report),no);
        }catch(Exception e){db.mysql.update("UPDATE t_customer_service_intent_release SET status='FAILED',validation_report_json=? WHERE release_no=? AND status IN ('BUILDING','VALIDATING')",write(Map.of("passed",false,"error","构建或完整性/评估校验失败，请核对数据与依赖后创建新版本")),no);}
    }
    public void activate(String no){Snapshot s=snapshot(no);assertReady(s);validate(s);
        JsonNode report=read(s.header.get("validation_report_json"));if(!report.path("passed").asBoolean() || (report.path("mockEmbedding").asBoolean()&&!"MOCK".equals(properties.getProvider())))throw incomplete();
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
