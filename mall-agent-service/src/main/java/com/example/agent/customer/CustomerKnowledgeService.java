package com.example.agent.customer;

import com.example.agent.config.RagProperties;
import com.example.agent.rag.TextTokenizer;
import com.example.agent.rag.embed.VectorFormat;
import com.fasterxml.jackson.databind.node.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import static com.example.agent.customer.CustomerJson.*;

/** Public knowledge retrieval with vector, keyword, hybrid and automatic degraded modes. */
@Service
public class CustomerKnowledgeService {
    private record Retrieval(ArrayNode sources,String mode,ObjectNode usage) {}
    private record DocCache(long expiresAt,List<Long> ids) {}
    private record Candidate(long id,double score) {}
    private static final String ELIGIBLE_DOCS="""
        SELECT d.id FROM t_knowledge_document d
         WHERE d.visibility='PUBLIC' AND d.status='ENABLED' AND d.doc_type IN (:types)
           AND d.version=(SELECT MAX(v.version) FROM t_knowledge_document v WHERE v.doc_no=d.doc_no AND v.status='ENABLED')
        """;
    private static final int DOC_CACHE_TTL_MS=5000;
    private final CustomerDatabase db;
    private final OnlineEmbedding embedding;
    private final CustomerProperties properties;
    private final RagProperties rag;
    private final Map<String,DocCache> docCache=new ConcurrentHashMap<>();

    public CustomerKnowledgeService(CustomerDatabase db,OnlineEmbedding embedding,CustomerProperties properties){this(db,embedding,properties,new RagProperties());}
    @Autowired public CustomerKnowledgeService(CustomerDatabase db,OnlineEmbedding embedding,CustomerProperties properties,RagProperties rag) {
        this.db=db;this.embedding=embedding;this.properties=properties;this.rag=rag;
    }

    public ArrayNode search(String query,List<String> requested,String run,String session,String agent,String callNo) {
        if(query==null||query.isBlank()||query.length()>512||query.chars().anyMatch(Character::isISOControl))
            throw error(400,"RAG_QUERY_INVALID","检索问题应为 1–512 个字符");
        List<String> types=CustomerToolPolicy.documentTypes(agent,requested);
        CustomerBudget budget=new CustomerBudget(properties.getRetrievalTimeoutMs());
        long start=System.nanoTime();Retrieval retrieval;
        if("MOCK".equals(properties.getProvider()))retrieval=new Retrieval(mockSources(query),"MOCK",object());
        else retrieval=retrieve(query,types,budget);
        budget.remaining();
        db.mysql.update("INSERT INTO t_knowledge_retrieval_log(log_no,run_no,session_no,tool_call_no,agent_name,scene,query_text,retrieval_mode,requested_mode,top_k,parent_hit_count,latency_ms,result_json) VALUES(?,?,?,?,?,'CUSTOMER_SERVICE',?,?,?,?,?,?,?)",
            id("KR"),run,session,callNo,agent,redact(query),retrieval.mode(),rag.getRetrieval().getDefaultMode().toUpperCase(Locale.ROOT),20,retrieval.sources().size(),(System.nanoTime()-start)/1000000,write(object().<ObjectNode>set("sources",retrieval.sources()).set("usage",retrieval.usage())));
        return retrieval.sources();
    }

    static ArrayNode mockSources(String query) {
        ArrayNode sources=array();
        if(query.matches("(?s).*(规则|售后|退款|提现|手续费|结算|担保|实名|绑定|退货).*"))
            sources.add(object().put("sourceNo","S1").put("docNo","MOCK-CUSTOMER-GUIDE").put("docVersion",1)
                .put("title","本地演示资料（非正式平台政策）").put("titlePath","客服使用说明").put("chunkNo","MOCK-CUSTOMER-GUIDE-P1")
                .put("content","当前为本地 Mock 演示模式。可通过交易工作台查看订单和售后入口；正式费用、时效与处理条件需查询已发布的平台规则或咨询人工客服。"));
        return sources;
    }

    private Retrieval retrieve(String query,List<String> types,CustomerBudget budget) {
        List<Long> docIds=eligibleDocs(types,budget);if(docIds.isEmpty())return new Retrieval(array(),"VECTOR",object());
        String requestedMode=rag.getRetrieval().getDefaultMode().toUpperCase(Locale.ROOT);
        boolean keywordOnly="KEYWORD".equals(requestedMode),hybrid="HYBRID".equals(requestedMode);
        List<Candidate> vector=new ArrayList<>(),keyword=new ArrayList<>();ObjectNode usage=object();String actualMode=requestedMode;
        if(!keywordOnly) {
            try {
                OnlineEmbedding.Result embedded=budget.call(9000,()->embedding.embedWithUsage(redact(query),embedding.model(),1536));
                usage.set("embeddingTokens",node(embedded.tokens()));
                vector=vectorCandidates(embedded.vector(),docIds,budget);
            } catch(RuntimeException unavailable) {
                if(!rag.getRetrieval().isFallbackEnabled())throw unavailable;
                actualMode="DEGRADED";
            }
        }
        if(keywordOnly||hybrid||vector.isEmpty()) {
            try { keyword=keywordCandidates(query,types,budget); }
            catch(RuntimeException unavailable) {
                if(vector.isEmpty())throw unavailable;
                actualMode="VECTOR_PARTIAL";
            }
        }
        if(keywordOnly)actualMode="KEYWORD";
        else if(!vector.isEmpty()&&!keyword.isEmpty())actualMode="HYBRID";
        else if(!vector.isEmpty()&&"HYBRID".equals(requestedMode))actualMode="VECTOR_PARTIAL";
        else if(!keyword.isEmpty()&&vector.isEmpty())actualMode="DEGRADED";
        List<Candidate> ranked=rrf(vector,keyword,rag.getRetrieval().getRrfK());
        return new Retrieval(loadParents(ranked,types,budget),actualMode,usage);
    }

    private List<Long> eligibleDocs(List<String> types,CustomerBudget budget) {
        String key=String.join("|",new TreeSet<>(types));long now=System.nanoTime();DocCache cached=docCache.get(key);
        if(cached!=null&&cached.expiresAt()>now)return cached.ids();
        List<Long> ids=budget.call(1000,()->new NamedParameterJdbcTemplate(db.mysql).queryForList(ELIGIBLE_DOCS,Map.of("types",types),Long.class));
        docCache.put(key,new DocCache(now+java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(DOC_CACHE_TTL_MS),List.copyOf(ids)));
        docCache.entrySet().removeIf(e->e.getValue().expiresAt()<=now);
        return ids;
    }

    private List<Candidate> vectorCandidates(float[] vector,List<Long> docs,CustomerBudget budget) {
        var hits=budget.call(2000,()->new NamedParameterJdbcTemplate(db.vector).queryForList("""
            SELECT chunk_id,1-(embedding <=> CAST(:v AS vector)) AS score FROM rag_chunk_embedding
             WHERE document_id IN (:ids) AND status='READY' AND visibility='PUBLIC'
               AND embedding_model=:model AND embedding_dim=1536
               AND 1-(embedding <=> CAST(:v AS vector)) >= :threshold
             ORDER BY embedding <=> CAST(:v AS vector) LIMIT 20
            """,Map.of("ids",docs,"v",VectorFormat.toLiteral(vector),"model",embedding.model(),"threshold",properties.getKnowledgeThreshold())));
        List<Candidate> result=new ArrayList<>(hits.size());for(var h:hits)result.add(new Candidate(((Number)h.get("chunk_id")).longValue(),((Number)h.get("score")).doubleValue()));return result;
    }

    private List<Candidate> keywordCandidates(String query,List<String> types,CustomerBudget budget) {
        List<String> tokens=TextTokenizer.tokens(query).stream().filter(t->!t.isBlank()).distinct().limit(8).toList();
        if(tokens.isEmpty())return List.of();
        String fullText=tokens.stream().map(t->t.replaceAll("[^\\p{IsHan}A-Za-z0-9]","")).filter(t->!t.isBlank()).distinct().collect(java.util.stream.Collectors.joining(" "));
        if(!fullText.isBlank())try {return fullTextCandidates(fullText,types,budget);}catch(RuntimeException noFullTextIndex) { /* ngram index is optional */ }
        Map<String,Object> params=new HashMap<>();params.put("types",types);StringBuilder score=new StringBuilder();StringBuilder matches=new StringBuilder();
        for(int i=0;i<tokens.size();i++) {
            String key="k"+i;params.put(key,"%"+tokens.get(i)+"%");if(i>0){score.append("+");matches.append(" OR ");}
            score.append("(CASE WHEN c.title_path LIKE :").append(key).append(" THEN 5 ELSE 0 END + CASE WHEN CAST(c.keywords_json AS CHAR) LIKE :").append(key).append(" THEN 3 ELSE 0 END + CASE WHEN c.chunk_content LIKE :").append(key).append(" THEN 1 ELSE 0 END)");
            matches.append("(c.title_path LIKE :").append(key).append(" OR CAST(c.keywords_json AS CHAR) LIKE :").append(key).append(" OR c.chunk_content LIKE :").append(key).append(")");
        }
        // Use portable LIKE/keywords matching; it needs no optional MySQL ngram index.
        String sql="""
            SELECT c.id AS chunk_id,(SCORE_EXPR) AS score FROM t_knowledge_chunk c
            JOIN t_knowledge_document d ON d.id=c.document_id
            WHERE c.chunk_level=2 AND c.status='ENABLED' AND c.embedding_status='READY'
              AND d.visibility='PUBLIC' AND d.status='ENABLED' AND d.doc_type IN (:types)
              AND d.version=(SELECT MAX(v.version) FROM t_knowledge_document v WHERE v.doc_no=d.doc_no AND v.status='ENABLED')
              AND (MATCH_EXPR)
            ORDER BY score DESC LIMIT 20
            """.replace("SCORE_EXPR",score).replace("MATCH_EXPR",matches);
        var hits=budget.call(1000,()->new NamedParameterJdbcTemplate(db.mysql).queryForList(sql,params));
        List<Candidate> result=new ArrayList<>(hits.size());for(var h:hits)result.add(new Candidate(((Number)h.get("chunk_id")).longValue(),((Number)h.get("score")).doubleValue()));return result;
    }

    private List<Candidate> fullTextCandidates(String query,List<String> types,CustomerBudget budget) {
        var hits=budget.call(1000,()->new NamedParameterJdbcTemplate(db.mysql).queryForList("""
            SELECT c.id AS chunk_id,
                   (MATCH(c.chunk_content) AGAINST(:query IN BOOLEAN MODE)
                    + CASE WHEN c.title_path LIKE :likeQuery THEN 5 ELSE 0 END
                    + CASE WHEN CAST(c.keywords_json AS CHAR) LIKE :likeQuery THEN 3 ELSE 0 END) AS score
              FROM t_knowledge_chunk c JOIN t_knowledge_document d ON d.id=c.document_id
             WHERE c.chunk_level=2 AND c.status='ENABLED' AND c.embedding_status='READY'
               AND d.visibility='PUBLIC' AND d.status='ENABLED' AND d.doc_type IN (:types)
               AND d.version=(SELECT MAX(v.version) FROM t_knowledge_document v WHERE v.doc_no=d.doc_no AND v.status='ENABLED')
               AND (MATCH(c.chunk_content) AGAINST(:query IN BOOLEAN MODE)>0 OR c.title_path LIKE :likeQuery OR CAST(c.keywords_json AS CHAR) LIKE :likeQuery)
             ORDER BY score DESC LIMIT 20
            """,Map.of("query",query,"likeQuery","%"+query+"%","types",types)));
        List<Candidate> result=new ArrayList<>(hits.size());for(var h:hits)result.add(new Candidate(((Number)h.get("chunk_id")).longValue(),((Number)h.get("score")).doubleValue()));return result;
    }

    private static List<Candidate> rrf(List<Candidate> vector,List<Candidate> keyword,int k) {
        Map<Long,Double> scores=new HashMap<>();
        for(int rank=0;rank<vector.size();rank++)scores.merge(vector.get(rank).id(),1.0/(k+rank+1),Double::sum);
        for(int rank=0;rank<keyword.size();rank++)scores.merge(keyword.get(rank).id(),1.0/(k+rank+1),Double::sum);
        return scores.entrySet().stream().map(e->new Candidate(e.getKey(),e.getValue())).sorted(Comparator.comparingDouble(Candidate::score).reversed()).limit(20).toList();
    }

    private ArrayNode loadParents(List<Candidate> hits,List<String> types,CustomerBudget budget) {
        if(hits.isEmpty())return array();
        var parents=budget.call(1000,()->new NamedParameterJdbcTemplate(db.mysql).queryForList("""
            SELECT c.id AS child_id,c.chunk_no AS child_no,p.id,p.doc_no,p.doc_version,p.chunk_no,p.title_path,p.chunk_content,d.title,d.doc_type
              FROM t_knowledge_chunk c JOIN t_knowledge_chunk p ON c.parent_chunk_id=p.id
              JOIN t_knowledge_document d ON d.id=p.document_id AND d.id=c.document_id
             WHERE c.id IN (:ids) AND c.chunk_level=2 AND p.chunk_level=1 AND c.embedding_status='READY'
               AND c.status='ENABLED' AND p.status='ENABLED' AND d.status='ENABLED' AND d.visibility='PUBLIC'
               AND c.doc_version=d.version AND p.doc_version=d.version AND d.doc_type IN (:types)
               AND d.version=(SELECT MAX(v.version) FROM t_knowledge_document v WHERE v.doc_no=d.doc_no AND v.status='ENABLED')
            """,Map.of("ids",hits.stream().map(Candidate::id).toList(),"types",types)));
        Map<Long,Map<String,Object>> byChild=new HashMap<>();parents.forEach(p->byChild.put(((Number)p.get("child_id")).longValue(),p));
        ArrayNode sources=array();Set<Long> seen=new HashSet<>();int chars=0;
        for(Candidate hit:hits) {
            var p=byChild.get(hit.id());if(p==null)continue;long parent=((Number)p.get("id")).longValue();if(!seen.add(parent))continue;
            String content=redact(p.get("chunk_content").toString());if(!CustomerOutputGuard.safeText(content))continue;
            if(sources.size()>=5||chars>=6000)break;
            content=content.substring(0,Math.min(content.length(),Math.min(1600,6000-chars)));chars+=content.length();
            sources.add(object().put("sourceNo","S"+(sources.size()+1)).put("chunkId",parent).put("docNo",p.get("doc_no").toString())
                .put("docVersion",((Number)p.get("doc_version")).intValue()).put("docType",p.get("doc_type").toString())
                .put("title",redact(p.get("title").toString())).put("titlePath",redact(Objects.toString(p.get("title_path"),"")))
                .put("chunkNo",p.get("chunk_no").toString()).put("score",hit.score()).put("content",content));
        }
        return sources;
    }
}
