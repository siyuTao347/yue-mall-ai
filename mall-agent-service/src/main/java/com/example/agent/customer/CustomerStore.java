package com.example.agent.customer;

import api.agent.AgentActor;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import org.springframework.stereotype.Repository;
import org.springframework.beans.factory.annotation.Autowired;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import static com.example.agent.customer.CustomerJson.*;

@Repository
public class CustomerStore {
    private static final Logger log=LoggerFactory.getLogger(CustomerStore.class);
    private static final ExecutorService SUMMARY_EXECUTOR=Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"customer-summary-updater");t.setDaemon(true);return t;});
    private final CustomerDatabase db;
    private final CustomerProperties props;
    private final CustomerRedisGate redisGate;
    private final com.example.agent.customer.memory.CustomerMemoryStore memoryStore;
    private final com.example.agent.customer.conversation.SessionEventStore eventStore;
    public CustomerStore(CustomerDatabase db,CustomerProperties props){this(db,props,null,null,null);}
    public CustomerStore(CustomerDatabase db,CustomerProperties props,CustomerRedisGate redisGate){this(db,props,redisGate,null,null);}
    public CustomerStore(CustomerDatabase db,CustomerProperties props,CustomerRedisGate redisGate,com.example.agent.customer.memory.CustomerMemoryStore memoryStore){this(db,props,redisGate,memoryStore,null);}
    @Autowired public CustomerStore(CustomerDatabase db,CustomerProperties props,@Autowired(required=false) CustomerRedisGate redisGate,@Autowired(required=false) com.example.agent.customer.memory.CustomerMemoryStore memoryStore,@Autowired(required=false) com.example.agent.customer.conversation.SessionEventStore eventStore){
        this.db=db;this.props=props;this.redisGate=redisGate;this.memoryStore=memoryStore;this.eventStore=eventStore;
    }
    @jakarta.annotation.PreDestroy
    public void close(){SUMMARY_EXECUTOR.shutdownNow();}
    public record Start(String runNo,Map<String,Object> session,JsonNode replay,String userMessageNo,Long sourceSequence,Long epoch,String subjectKey){
        public Start(String runNo,Map<String,Object> session,JsonNode replay){
            this(runNo,session,replay,null,null,null,null);
        }
    }
    public Map<String,Object> create(AgentActor actor){String no=id("AS");db.mysql.update("INSERT INTO t_agent_session(session_no,user_id,user_role,status,last_active_time) VALUES(?,?,?,'ACTIVE',now(3))",no,actor.userId(),actor.role());return Map.of("sessionNo",no,"status","ACTIVE");}
    public Map<String,Object> own(AgentActor actor,String no){var rows=db.mysql.queryForList("SELECT *,last_active_time<DATE_SUB(now(3),INTERVAL 24 HOUR) AS expired FROM t_agent_session WHERE session_no=? AND user_id=?",no,actor.userId());if(rows.isEmpty())throw error(403,"AGENT_PERMISSION_DENIED","无法访问此会话");return rows.getFirst();}
    public ObjectNode session(AgentActor actor,String no){var s=own(actor,no);ObjectNode result=object().put("sessionNo",no).put("status",truth(s.get("expired"))&&"ACTIVE".equals(s.get("status"))?"EXPIRED":s.get("status").toString());
        ArrayNode messages=array();var rows=db.mysql.queryForList("SELECT m.message_no,m.role,m.content,m.run_no,m.created_time,r.result_json AS agent_result_json FROM (SELECT * FROM t_agent_message WHERE session_no=? ORDER BY id DESC LIMIT 100) m LEFT JOIN t_agent_run r ON m.role='AGENT' AND r.run_no=m.run_no ORDER BY m.id",no);
        for(var row:rows){ObjectNode m=node(row).deepCopy();Object raw=row.get("agent_result_json");m.remove("agent_result_json");if(raw!=null)m.set("result",read(raw));messages.add(m);}result.set("messages",messages);return result;}
    public void close(AgentActor actor,String no){db.tx.executeWithoutResult(tx->{own(actor,no);db.mysql.queryForList("SELECT user_id FROM t_agent_user_gate WHERE user_id=? FOR UPDATE",actor.userId());db.mysql.queryForList("SELECT id FROM t_agent_session WHERE session_no=? FOR UPDATE",no);if(db.mysql.queryForObject("SELECT COUNT(*) FROM t_agent_run WHERE session_no=? AND status='RUNNING' AND created_time>DATE_SUB(now(3),INTERVAL 100 SECOND)",Integer.class,no)>0)throw error(409,"AGENT_BUSY","正在处理消息，请稍后关闭");db.mysql.update("UPDATE t_agent_session SET status='CLOSED',closed_time=now(3) WHERE session_no=? AND user_id=?",no,actor.userId());});}
    public Start start(AgentActor actor,String no,String clientId,String input,String requestHash){
        String run=id("AR");int leaseSeconds=(props.getRunTimeoutMs()+999)/1000+5;boolean redisEnabled=false,redisAcquired=false,redisReplay=false;
        if(redisGate!=null) {
            String gate=redisGate.acquire(actor.userId(),no,clientId,requestHash,run,leaseSeconds);
            switch(gate) {
                case "ACQUIRED"->{redisEnabled=true;redisAcquired=true;}
                case "REPLAY"->{redisEnabled=true;redisReplay=true;}
                case "IDEMPOTENCY_CONFLICT"->throw error(409,"AGENT_IDEMPOTENCY_CONFLICT","消息标识已被其他内容使用");
                case "BUSY"->throw error(429,"AGENT_BUSY","你已有消息正在处理");
                case "RATE_LIMITED"->throw error(429,"AGENT_RATE_LIMITED","每分钟最多发送 5 条消息，请稍后再试");
                default->{/* Redis unavailable: use the existing MySQL gate. */}
            }
        }
        final boolean useRedis=redisEnabled,replayInRedis=redisReplay;final String runNo=run;
        try {
            Start result=db.tx.execute(tx->{
                own(actor,no);
                db.mysql.update("INSERT IGNORE INTO t_agent_user_gate(user_id) VALUES(?)",actor.userId());
                String gateSql=useRedis?"SELECT *,lease_until>now(3) AS busy FROM t_agent_user_gate WHERE user_id=?":"SELECT *,lease_until>now(3) AS busy FROM t_agent_user_gate WHERE user_id=? FOR UPDATE";
                var gate=db.mysql.queryForMap(gateSql,actor.userId());
                if(!truth(gate.get("busy"))&&gate.get("lease_run_no")!=null)recoverExpired(gate.get("lease_run_no").toString());
                var s=db.mysql.queryForMap("SELECT *,last_active_time<DATE_SUB(now(3),INTERVAL 24 HOUR) AS expired FROM t_agent_session WHERE session_no=? AND user_id=? FOR UPDATE",no,actor.userId());
                var previous=db.mysql.queryForList("SELECT r.run_no,r.request_hash,r.result_json FROM t_agent_message m JOIN t_agent_run r ON r.run_no=m.run_no WHERE m.session_no=? AND m.client_message_id=?",no,clientId);
                if(!previous.isEmpty()){var p=previous.getFirst();if(!requestHash.equals(p.get("request_hash")))throw error(409,"AGENT_IDEMPOTENCY_CONFLICT","消息标识已被其他内容使用");if(p.get("result_json")==null)throw error(409,"AGENT_RUN_PENDING","原消息仍在处理或已中断，请查询会话状态");return new Start(p.get("run_no").toString(),s,read(p.get("result_json")));}
                if(replayInRedis)throw error(409,"AGENT_RUN_PENDING","原消息仍在处理或已中断，请查询会话状态");
                if(!"ACTIVE".equals(s.get("status"))||(s.get("service_mode")!=null&&!"BOT".equals(s.get("service_mode").toString())))throw error(409,"AGENT_SESSION_INACTIVE","会话已结束或已转人工");
                if(truth(s.get("expired")))throw error(409,"AGENT_SESSION_EXPIRED","会话已过期，请创建新会话");
                if(truth(gate.get("busy")))throw error(429,"AGENT_BUSY","你已有消息正在处理");
                if(!useRedis){int count=db.mysql.queryForObject("SELECT COUNT(*) FROM t_agent_run r JOIN t_agent_session s ON r.session_no=s.session_no WHERE s.user_id=? AND r.created_time>DATE_SUB(now(3),INTERVAL 1 MINUTE)",Integer.class,actor.userId());
                    if(count>=5)throw error(429,"AGENT_RATE_LIMITED","每分钟最多发送 5 条消息，请稍后再试");}
                db.mysql.update("UPDATE t_agent_user_gate SET lease_run_no=?,lease_until=TIMESTAMPADD(SECOND,?,now(3)) WHERE user_id=?",runNo,leaseSeconds,actor.userId());
                String userMsgNo=id("AM");
                db.mysql.update("INSERT INTO t_agent_run(run_no,session_no,status,trace_id,request_hash,prompt_version,provider,master_model_name,model_name) VALUES(?,?,'RUNNING',?,?,'customer-agent-v3',?,?,?)",runNo,no,Objects.toString(api.context.TraceContext.getTraceId(),runNo),requestHash,props.getProvider(),props.getMasterModel(),props.getResponseModel());
                db.mysql.update("INSERT INTO t_agent_message(message_no,session_no,run_no,client_message_id,role,content) VALUES(?,?,?,?,'USER',?)",userMsgNo,no,runNo,clientId,input);
                db.mysql.update("UPDATE t_agent_session SET last_active_time=now(3),message_count=message_count+1 WHERE session_no=?",no);
                if(eventStore!=null){
                    ObjectNode userEvt=object().put("messageNo",userMsgNo).put("sessionNo",no).put("runNo",runNo).put("clientMessageId",clientId).put("role","USER").put("content",input);
                    eventStore.appendPublicEventInTx(no,"message.created",userEvt);
                }
                Long allocatedSeq=null,epochVal=null;String subjKey=null;boolean writeEnabled=false;
                if(memoryStore!=null){
                    try{
                        var setting=memoryStore.getOrCreateSetting(actor.userId());
                        writeEnabled=setting.writeEnabled();epochVal=setting.epoch();subjKey=setting.subjectKey();
                        if(writeEnabled)allocatedSeq=memoryStore.allocateNextSourceSequence(actor.userId());
                    }catch(Exception e){log.warn("CustomerStore: memory setting init failed: {}",e.getMessage());}
                }
                ObjectNode captureCtx=object().put("userMessageNo",userMsgNo).put("userMessage",input).put("writeEnabled",writeEnabled).put("captureAllowed",writeEnabled).put("epoch",epochVal!=null?epochVal:1L).put("sourceSequence",allocatedSeq!=null?allocatedSeq:0L).put("subjectKey",subjKey!=null?subjKey:"");
                db.mysql.update("UPDATE t_agent_run SET memory_capture_context_json=? WHERE run_no=?",write(captureCtx),runNo);
                return new Start(runNo,s,null,userMsgNo,allocatedSeq,epochVal,subjKey);
            });
            if(redisAcquired&&result!=null&&result.replay()!=null){redisGate.complete(actor.userId(),runNo);redisGate.record(actor.userId(),no,clientId,requestHash,result.runNo());}
            else if(redisAcquired&&result!=null)redisGate.record(actor.userId(),no,clientId,requestHash,runNo);
            return result;
        } catch(RuntimeException failure) {
            if(redisAcquired)redisGate.abort(actor.userId(),no,clientId,requestHash,runNo);
            throw failure;
        }
    }
    private void recoverExpired(String run) {
        var records=db.mysql.queryForList("SELECT session_no,trace_id FROM t_agent_run WHERE run_no=? AND status='RUNNING' FOR UPDATE",run);
        if(records.isEmpty())return;
        ObjectNode response=object().put("schemaVersion",2).put("runNo",run).put("traceId",Objects.toString(records.getFirst().get("trace_id"),run)).put("errorCode","AGENT_TIMEOUT")
            .put("answer","上次请求已超时，请重新发送问题，或联系人工。").put("handoffRequired",false);
        response.set("citations",array());response.set("suggestedActions",array());response.set("intentResults",array());
        String session=records.getFirst().get("session_no").toString();
        var handoffs=db.mysql.queryForList("SELECT handoff_no FROM t_agent_handoff WHERE run_no=? LIMIT 1",run);
        if(!handoffs.isEmpty())response.put("handoffRequired",true).put("handoffNo",handoffs.getFirst().get("handoff_no").toString());
        db.mysql.update("UPDATE t_agent_run SET status='TIMEOUT',error_code='AGENT_TIMEOUT',result_json=?,finished_time=now(3) WHERE run_no=? AND status='RUNNING'",write(response),run);
        db.mysql.update("INSERT INTO t_agent_message(message_no,session_no,run_no,role,content,citations_json) VALUES(?,?,?,'AGENT',?,'[]')",id("AM"),session,run,response.path("answer").asText());
    }
    private static boolean truth(Object value){return Boolean.TRUE.equals(value)||(value instanceof Number n&&n.intValue()!=0);}
    public JsonNode history(String session){var rows=db.mysql.queryForList("SELECT role,content FROM (SELECT id,role,content FROM t_agent_message WHERE session_no=? ORDER BY id DESC LIMIT 8) m ORDER BY id",session);return node(rows);}
    public void finish(AgentActor actor,String session,String run,ObjectNode master,ArrayNode results,ObjectNode response,ObjectNode slots,ArrayNode path,int tools,int inputTokens,int outputTokens,long elapsed,String state){
        finish(actor,session,run,master,results,response,slots,path,tools,inputTokens,outputTokens,elapsed,state,false,null);
    }
    public void finish(AgentActor actor,String session,String run,ObjectNode master,ArrayNode results,ObjectNode response,ObjectNode slots,ArrayNode path,int tools,int inputTokens,int outputTokens,long elapsed,String state,boolean embeddingCacheHit,Integer ttftMs){
        finish(actor,session,run,master,results,response,slots,path,tools,inputTokens,outputTokens,elapsed,state,embeddingCacheHit,ttftMs,null,null);
    }
    public void finish(AgentActor actor,String session,String run,ObjectNode master,ArrayNode results,ObjectNode response,ObjectNode slots,ArrayNode path,int tools,int inputTokens,int outputTokens,long elapsed,String state,boolean embeddingCacheHit,Integer ttftMs,JsonNode memoryEvidence,JsonNode memoryCaptureContext){
        db.tx.executeWithoutResult(tx->{var gate=db.mysql.queryForMap("SELECT lease_run_no FROM t_agent_user_gate WHERE user_id=? FOR UPDATE",actor.userId());if(!run.equals(gate.get("lease_run_no")))throw error(409,"AGENT_LEASE_LOST","运行已过期");
            int n=db.mysql.update("UPDATE t_agent_run SET status=?,error_code=?,master_result_json=?,specialist_results_json=?,result_json=?,graph_path_json=?,tool_call_count=?,tool_rounds=?,input_tokens=?,output_tokens=?,latency_ms=?,embedding_cache_hit=?,ttft_ms=?,memory_evidence_json=?,finished_time=now(3) WHERE run_no=? AND status='RUNNING'",
                state,response.path("errorCode").asText(null),write(master),write(results),write(response),write(path),tools,Math.min(tools,3),inputTokens,outputTokens,elapsed,embeddingCacheHit?1:0,ttftMs,memoryEvidence!=null?write(memoryEvidence):null,run);
            if(n!=1)throw error(409,"AGENT_RUN_FINISHED","消息已处理");

            var sRow=db.mysql.queryForMap("SELECT service_mode,control_epoch FROM t_agent_session WHERE session_no=? FOR UPDATE",session);
            String serviceMode=sRow.get("service_mode")!=null?sRow.get("service_mode").toString():"BOT";
            boolean isBotMode="BOT".equalsIgnoreCase(serviceMode);
            String agentMsgNo=id("AM");

            if(isBotMode){
                db.mysql.update("INSERT INTO t_agent_message(message_no,session_no,run_no,role,content,citations_json) VALUES(?,?,?,'AGENT',?,?)",agentMsgNo,session,run,response.path("answer").asText(),write(response.path("citations")));
                ArrayNode chunkIds=array();for(var c:response.path("citations"))if(c.has("chunkId"))chunkIds.add(c.path("chunkId"));
                db.mysql.update("UPDATE t_agent_run SET knowledge_chunk_ids_json=? WHERE run_no=?",write(chunkIds),run);
                db.mysql.update("UPDATE t_agent_session SET slot_json=?,last_intent=?,message_count=message_count+1,last_active_time=now(3) WHERE session_no=?",write(slots),master.path("intents").path(0).path("code").asText("OTHER"),session);
                if(eventStore!=null){
                    ObjectNode agentEvt=object().put("messageNo",agentMsgNo).put("sessionNo",session).put("runNo",run).put("role","AGENT").put("content",response.path("answer").asText()).set("citations",response.path("citations"));
                    if(response.has("richCard"))agentEvt.set("richCard",response.get("richCard"));
                    eventStore.appendPublicEventInTx(session,"message.created",agentEvt);
                }
            } else {
                log.info("CustomerStore: AI run {} completed but session {} is in mode {}, suppressing public AGENT message",run,session,serviceMode);
                try{
                    db.mysql.update("INSERT INTO t_agent_copilot_task(task_id,session_no,task_type,payload_json,status,created_time) VALUES(?,?,'DRAFT_ANSWER',?,'COMPLETED',now(3))",id("CP"),session,write(object().put("answer",response.path("answer").asText()).put("runNo",run)));
                }catch(Exception ex){log.warn("CustomerStore: failed to record copilot draft: {}",ex.getMessage());}
                db.mysql.update("UPDATE t_agent_session SET last_active_time=now(3) WHERE session_no=?",session);
            }
            db.mysql.update("UPDATE t_agent_user_gate SET lease_run_no=NULL,lease_until=NULL WHERE user_id=? AND lease_run_no=?",actor.userId(),run);

            if(memoryStore!=null&&"SUCCEEDED".equals(state)&&memoryCaptureContext!=null&&memoryCaptureContext.path("captureAllowed").asBoolean(false)){
                try{
                    String msgNo=memoryCaptureContext.path("userMessageNo").asText();
                    if(!msgNo.isBlank()){
                        String eventId=id("EV");
                        String dedupeKey="CAPTURE_"+msgNo;
                        long epoch=memoryCaptureContext.path("epoch").asLong(1);
                        long seq=memoryCaptureContext.path("sourceSequence").asLong(0);
                        String subjectKey=memoryCaptureContext.path("subjectKey").asText("");
                        ObjectNode payload=object().put("userMessage",memoryCaptureContext.path("userMessage").asText("")).put("sourceMessageNo",msgNo).put("sourceRunNo",run).put("sourceSessionNo",session);
                        memoryStore.appendCaptureInCurrentTransaction(eventId,dedupeKey,actor.userId(),subjectKey,epoch,seq,payload,Objects.toString(api.context.TraceContext.getTraceId(),run));
                    }
                }catch(Exception e){log.warn("CustomerStore: Outbox append failed: {}",e.getMessage());}
            }
        });
        if(redisGate!=null)redisGate.complete(actor.userId(),run);
        SUMMARY_EXECUTOR.execute(()->updateSummary(session,run,master,slots,response));
    }
    private void updateSummary(String session,String run,ObjectNode master,ObjectNode slots,ObjectNode response){
        try {
            String previous=Objects.toString(db.mysql.queryForObject("SELECT summary FROM t_agent_session WHERE session_no=?",String.class,session),"");
            String runTag=clip(run,18);
            if(previous.contains("["+runTag+"]")||previous.contains("["+run+"]"))return;
            List<String> turns=new ArrayList<>();if(!previous.isBlank())turns.addAll(Arrays.asList(previous.split("\\n")));
            List<String> intentTags=new ArrayList<>();
            for(JsonNode intent:master.path("intents"))intentTags.add(intent.path("code").asText("OTHER")+":"+clip(write(intent.path("slots")),16));
            StringBuilder turn=new StringBuilder("[").append(runTag).append("]I:").append(clip(String.join(",",intentTags),16))
                .append("|Q:").append(clip(master.path("originalQuestion").asText(""),10))
                .append("|A:").append(clip(response.path("answer").asText(""),15));
            JsonNode pending=slots.path("pendingIntents");if(pending.isArray()&&!pending.isEmpty())turn.append("|P:").append(clip(pendingSummary(pending),8));
            turns.add(clip(turn.toString(),80));String joined=String.join("\n",turns);
            while(joined.length()>800&&turns.size()>1){turns.removeFirst();joined=String.join("\n",turns);}
            if(joined.length()>800)joined=joined.substring(joined.length()-800);
            db.mysql.update("UPDATE t_agent_session SET summary=? WHERE session_no=?",joined,session);
        } catch(RuntimeException e) {log.warn("CustomerAgent rolling summary update failed for session {}: {}",session,e.getMessage());}
    }
    private static String pendingSummary(JsonNode pending){
        List<String> values=new ArrayList<>();for(JsonNode item:pending)values.add(item.path("code").asText("OTHER")+" "+item.path("missingSlots").toString());
        return clip(String.join(", ",values),180);
    }
    private static String clip(String value,int max){String safe=redact(Objects.toString(value,"" )).replaceAll("\\s+"," ").strip();return safe.length()<=max?safe:safe.substring(0,max);}
    public void tool(String run,String session,String instance,String agent,String name,int sequence,Object args,Object result,boolean success,long ms){toolWithId(id("AT"),run,session,instance,agent,name,sequence,args,result,success,ms);}
    public void toolWithId(String callNo,String run,String session,String instance,String agent,String name,int sequence,Object args,Object result,boolean success,long ms){db.mysql.update("INSERT INTO t_agent_tool_call(call_no,run_no,session_no,agent_name,tool_name,call_sequence,input_json,output_json,success,elapsed_ms,error_code) VALUES(?,?,?,?,?,?,?,?,?,?,?)",callNo,run,session,agent,name,sequence,write(Map.of("intentInstanceId",instance,"arguments",args)),write(result),success?1:0,ms,success?null:"AGENT_TOOL_FAILED");}
    public String handoff(AgentActor actor,String session,String run,String reason,Object context){return db.tx.execute(tx->{
        var lease=db.mysql.queryForMap("SELECT lease_run_no,lease_until>now(3) AS busy FROM t_agent_user_gate WHERE user_id=? FOR UPDATE",actor.userId());
        if(!run.equals(lease.get("lease_run_no"))||!truth(lease.get("busy")))throw error(409,"AGENT_LEASE_LOST","运行已过期");
        var existing=db.mysql.queryForList("SELECT handoff_no FROM t_agent_handoff WHERE session_no=? AND status IN ('OPEN','PROCESSING') LIMIT 1",session);if(!existing.isEmpty())return existing.getFirst().get("handoff_no").toString();
        String no=id("AH");db.mysql.update("INSERT INTO t_agent_handoff(handoff_no,session_no,run_no,user_id,reason,priority,context_json) VALUES(?,?,?,?,?,?,?)",no,session,run,actor.userId(),reason,node(context).path("riskLevel").asText().equals("HIGH")?"HIGH":"NORMAL",write(context));db.mysql.update("UPDATE t_agent_session SET status='HANDOFF' WHERE session_no=? AND user_id=?",session,actor.userId());return no;});}
    public void updateHandoffContext(String no,String run,JsonNode master,JsonNode results,JsonNode response) {
        ObjectNode context=object().put("runNo",run);context.set("master",master);context.set("attemptedResults",results);context.set("response",response);
        var sessions=db.mysql.queryForList("SELECT s.session_no,s.summary,s.slot_json FROM t_agent_handoff h JOIN t_agent_session s ON s.session_no=h.session_no WHERE h.handoff_no=?",no);
        if(!sessions.isEmpty()) {
            var session=sessions.getFirst();context.put("summary",Objects.toString(session.get("summary"),""));
            context.set("previousContext",read(session.get("slot_json")));context.set("recentMessages",history(session.get("session_no").toString()));
        }
        db.mysql.update("UPDATE t_agent_handoff SET context_json=? WHERE handoff_no=? AND run_no=?",write(context),no,run);
    }
    public void feedback(AgentActor actor,String run,int rating,String comment){var rows=db.mysql.queryForList("SELECT session_no FROM t_agent_run WHERE run_no=?",run);if(rows.isEmpty())throw error(403,"AGENT_PERMISSION_DENIED","无法访问此运行");String session=rows.getFirst().get("session_no").toString();own(actor,session);if(rating<1||rating>5)throw error(400,"AGENT_FEEDBACK_INVALID","评分应为 1–5");db.mysql.update("INSERT INTO t_agent_feedback(session_no,run_no,user_id,rating,comment) VALUES(?,?,?,?,?) ON DUPLICATE KEY UPDATE rating=?,comment=?",session,run,actor.userId(),rating,redact(comment),rating,redact(comment));}
    public List<Map<String,Object>> adminList(boolean handoffs){return db.mysql.queryForList(handoffs?"SELECT handoff_no,session_no,run_no,reason,priority,status,created_time FROM t_agent_handoff ORDER BY id DESC LIMIT 100":"SELECT run_no,session_no,status,latency_ms,created_time FROM t_agent_run ORDER BY id DESC LIMIT 100");}
    public Object adminDetail(boolean handoff,String no){var rows=db.mysql.queryForList(handoff?"SELECT * FROM t_agent_handoff WHERE handoff_no=?":"SELECT * FROM t_agent_run WHERE run_no=?",no);if(rows.isEmpty())throw error(404,"AGENT_NOT_FOUND","记录不存在");return rows.getFirst();}
    public Object tools(String run){return db.mysql.queryForList("SELECT * FROM t_agent_tool_call WHERE run_no=? ORDER BY call_sequence",run);}
    /** Admin-only evaluation evidence. Session/publication metadata is observed now, not a historical snapshot. */
    public Object evaluationEvidence(String run) {
        Map<String,Object> detail=new LinkedHashMap<>((Map<String,Object>)adminDetail(false,run));
        var session=db.mysql.queryForMap("SELECT user_id,slot_json FROM t_agent_session WHERE session_no=?",detail.get("session_no"));
        detail.put("actor_user_id",session.get("user_id"));
        detail.put("session_state",read(session.get("slot_json")));
        detail.put("metadata_observed_at",java.time.Instant.now().toString());
        detail.put("metadata_scope","current_session_and_publication_state");
        detail.put("implementation_sha256",implementationHash());
        var releases=db.mysql.queryForList("SELECT r.release_no,r.status,r.manifest_hash,r.embedding_model,r.embedding_dim FROM t_customer_service_intent_active_release a JOIN t_customer_service_intent_release r ON r.release_no=a.active_release_no WHERE a.scene='CUSTOMER_SERVICE'");
        detail.put("intent_release",releases.isEmpty()?Map.of("state","UNPUBLISHED"):releases.getFirst());
        var handoffs=db.mysql.queryForList("SELECT handoff_no,user_id,session_no,run_no,status FROM t_agent_handoff WHERE run_no=?",run);
        if(!handoffs.isEmpty())detail.put("handoff",handoffs.getFirst());
        List<Map<String,Object>> sources=new ArrayList<>();Set<String> seen=new HashSet<>();
        for(var citation:read(detail.get("result_json")).path("citations")) {
            String no=citation.path("docNo").asText();int version=citation.path("docVersion").asInt();
            if(!seen.add(no+":"+version))continue;
            sources.addAll(db.mysql.queryForList("SELECT doc_no,version,status,visibility,index_status,content_hash FROM t_knowledge_document WHERE doc_no=? AND version=?",no,version));
        }
        detail.put("knowledge_sources",sources);return detail;
    }
    private static String implementationHash() {
        try {
            var digest=java.security.MessageDigest.getInstance("SHA-256");
            for(Class<?> type:List.of(CustomerStore.class,CustomerMaster.class,CustomerRunner.class,CustomerModels.class,
                    CustomerController.class,CustomerOutputGuard.class,CustomerToolPolicy.class,CustomerKnowledgeService.class,
                    IntentReleaseService.class,CustomerBudget.class,CustomerJson.class,CustomerProperties.class,OnlineEmbedding.class,
                    CustomerPromptRegistry.class,CustomerContextWindowBuilder.class,CustomerStreamingAnswerGuard.class,CustomerRedisGate.class)) {
                digest.update(type.getName().getBytes(java.nio.charset.StandardCharsets.UTF_8));
                try(var input=type.getResourceAsStream(type.getSimpleName()+".class")) {
                    if(input==null)throw new IllegalStateException("Missing implementation bytecode");
                    digest.update(input.readAllBytes());
                }
            }
            return java.util.HexFormat.of().formatHex(digest.digest());
        }catch(java.io.IOException|java.security.NoSuchAlgorithmException e){throw new IllegalStateException("Cannot fingerprint evaluation implementation",e);}
    }
    public void process(long admin,String no,boolean resolve,String resolution){int n=resolve?db.mysql.update("UPDATE t_agent_handoff SET status='RESOLVED',resolved_time=now(3),resolution=? WHERE handoff_no=? AND status='PROCESSING' AND handler_id=?",redact(resolution),no,admin):db.mysql.update("UPDATE t_agent_handoff SET status='PROCESSING',handler_id=?,claimed_time=now(3) WHERE handoff_no=? AND status='OPEN'",admin,no);if(n!=1)throw error(409,"AGENT_HANDOFF_CONFLICT","记录状态已变化或由其他客服处理");}
}
