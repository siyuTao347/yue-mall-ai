package com.example.agent.customer;

import com.example.agent.rag.embed.VectorFormat;
import com.fasterxml.jackson.databind.node.*;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import java.util.*;
import static com.example.agent.customer.CustomerJson.*;

/** Public knowledge retrieval. MySQL rechecks publication and chunk ownership after vector recall. */
@Service
public class CustomerKnowledgeService {
    private final CustomerDatabase db;
    private final OnlineEmbedding embedding;
    private final CustomerProperties properties;
    public CustomerKnowledgeService(CustomerDatabase db,OnlineEmbedding embedding,CustomerProperties properties) {
        this.db=db;this.embedding=embedding;this.properties=properties;
    }
    public ArrayNode search(String query,List<String> requested,String run,String session,String agent,String callNo) {
        if(query==null||query.isBlank()||query.length()>512||query.chars().anyMatch(Character::isISOControl))
            throw error(400,"RAG_QUERY_INVALID","检索问题应为 1–512 个字符");
        List<String> types=CustomerToolPolicy.documentTypes(agent,requested);
        CustomerBudget budget=new CustomerBudget(properties.getRetrievalTimeoutMs());
        long start=System.nanoTime();
        ArrayNode sources;ObjectNode usage=object();
        if("MOCK".equals(properties.getProvider())) sources=mockSources(query);
        else sources=retrieve(query,types,budget,usage);
        budget.remaining();
        db.mysql.update("INSERT INTO t_knowledge_retrieval_log(log_no,run_no,session_no,tool_call_no,agent_name,scene,query_text,retrieval_mode,requested_mode,top_k,parent_hit_count,latency_ms,result_json) VALUES(?,?,?,?,?,'CUSTOMER_SERVICE',?,?,?,?,?,?,?)",
            id("KR"),run,session,callNo,agent,redact(query),"MOCK".equals(properties.getProvider())?"DEGRADED":"VECTOR","VECTOR",20,sources.size(),(System.nanoTime()-start)/1000000,write(object().<ObjectNode>set("sources",sources).set("usage",usage)));
        return sources;
    }
    static ArrayNode mockSources(String query) {
        ArrayNode sources=array();
        if(query.matches("(?s).*(规则|售后|退款|提现|手续费|结算|担保|实名|绑定|退货).*"))
            sources.add(object().put("sourceNo","S1").put("docNo","MOCK-CUSTOMER-GUIDE").put("docVersion",1)
                .put("title","本地演示资料（非正式平台政策）").put("titlePath","客服使用说明").put("chunkNo","MOCK-CUSTOMER-GUIDE-P1")
                .put("content","当前为本地 Mock 演示模式。可通过交易工作台查看订单和售后入口；正式费用、时效与处理条件需查询已发布的平台规则或咨询人工客服。"));
        return sources;
    }
    private ArrayNode retrieve(String query,List<String> types,CustomerBudget budget,ObjectNode usage) {
        var docs=budget.call(1000,()->new NamedParameterJdbcTemplate(db.mysql).queryForList("""
            SELECT d.id FROM t_knowledge_document d
             WHERE d.visibility='PUBLIC' AND d.status='ENABLED' AND d.doc_type IN (:types)
               AND d.version=(SELECT MAX(v.version) FROM t_knowledge_document v WHERE v.doc_no=d.doc_no AND v.status='ENABLED')
            """,Map.of("types",types)));
        ArrayNode sources=array();if(docs.isEmpty())return sources;
        OnlineEmbedding.Result embedded=budget.call(9000,()->embedding.embedWithUsage(redact(query),embedding.model(),1536));
        usage.set("embeddingTokens",node(embedded.tokens()));float[] vector=embedded.vector();
        var hits=budget.call(2000,()->new NamedParameterJdbcTemplate(db.vector).queryForList("""
            SELECT chunk_id,1-(embedding <=> CAST(:v AS vector)) AS score FROM rag_chunk_embedding
             WHERE document_id IN (:ids) AND status='READY' AND visibility='PUBLIC'
               AND embedding_model=:model AND embedding_dim=1536
               AND 1-(embedding <=> CAST(:v AS vector)) >= :threshold
             ORDER BY embedding <=> CAST(:v AS vector) LIMIT 20
            """,Map.of("ids",docs.stream().map(d->d.get("id")).toList(),"v",VectorFormat.toLiteral(vector),"model",embedding.model(),"threshold",properties.getKnowledgeThreshold())));
        if(hits.isEmpty())return sources;
        var parents=budget.call(1000,()->new NamedParameterJdbcTemplate(db.mysql).queryForList("""
            SELECT c.id AS child_id,c.chunk_no AS child_no,p.id,p.doc_no,p.doc_version,p.chunk_no,p.title_path,p.chunk_content,d.title,d.doc_type
              FROM t_knowledge_chunk c JOIN t_knowledge_chunk p ON c.parent_chunk_id=p.id
              JOIN t_knowledge_document d ON d.id=p.document_id AND d.id=c.document_id
             WHERE c.id IN (:ids) AND c.chunk_level=2 AND p.chunk_level=1 AND c.embedding_status='READY'
               AND c.status='ENABLED' AND p.status='ENABLED' AND d.status='ENABLED' AND d.visibility='PUBLIC'
               AND c.doc_version=d.version AND p.doc_version=d.version AND d.doc_type IN (:types)
               AND d.version=(SELECT MAX(v.version) FROM t_knowledge_document v WHERE v.doc_no=d.doc_no AND v.status='ENABLED')
            """,Map.of("ids",hits.stream().map(h->h.get("chunk_id")).toList(),"types",types)));
        Map<Long,Map<String,Object>> byChild=new HashMap<>();parents.forEach(p->byChild.put(((Number)p.get("child_id")).longValue(),p));
        Set<Long> seen=new HashSet<>();int chars=0;
        for(var hit:hits) {
            var p=byChild.get(((Number)hit.get("chunk_id")).longValue());if(p==null)continue;
            long parent=((Number)p.get("id")).longValue();if(!seen.add(parent))continue;
            String content=redact(p.get("chunk_content").toString());
            // Untrusted knowledge can contain injection or prohibited instructions, including inside citations.
            if(!CustomerOutputGuard.safeText(content))continue;
            if(sources.size()>=5||chars>=6000)break;
            content=content.substring(0,Math.min(content.length(),Math.min(1600,6000-chars)));chars+=content.length();
            sources.add(object().put("sourceNo","S"+(sources.size()+1)).put("chunkId",parent).put("docNo",p.get("doc_no").toString())
                .put("docVersion",((Number)p.get("doc_version")).intValue()).put("docType",p.get("doc_type").toString())
                .put("title",redact(p.get("title").toString())).put("titlePath",redact(Objects.toString(p.get("title_path"),"")))
                .put("chunkNo",p.get("chunk_no").toString()).put("score",((Number)hit.get("score")).doubleValue()).put("content",content));
        }
        return sources;
    }
}
