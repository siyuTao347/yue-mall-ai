package com.example.agent.customer;

import api.agent.AgentActor;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import org.springframework.stereotype.Repository;
import java.util.*;
import static com.example.agent.customer.CustomerJson.*;

@Repository
public class CustomerStore {
    private final CustomerDatabase db;
    private final CustomerProperties props;
    public CustomerStore(CustomerDatabase db,CustomerProperties props){this.db=db;this.props=props;}
    public record Start(String runNo,Map<String,Object> session,JsonNode replay){}
    public Map<String,Object> create(AgentActor actor){String no=id("AS");db.mysql.update("INSERT INTO t_agent_session(session_no,user_id,user_role,status,last_active_time) VALUES(?,?,?,'ACTIVE',now(3))",no,actor.userId(),actor.role());return Map.of("sessionNo",no,"status","ACTIVE");}
    public Map<String,Object> own(AgentActor actor,String no){var rows=db.mysql.queryForList("SELECT *,last_active_time<DATE_SUB(now(3),INTERVAL 24 HOUR) AS expired FROM t_agent_session WHERE session_no=? AND user_id=?",no,actor.userId());if(rows.isEmpty())throw error(403,"AGENT_PERMISSION_DENIED","无法访问此会话");return rows.getFirst();}
    public ObjectNode session(AgentActor actor,String no){var s=own(actor,no);ObjectNode result=object().put("sessionNo",no).put("status",truth(s.get("expired"))&&"ACTIVE".equals(s.get("status"))?"EXPIRED":s.get("status").toString());
        ArrayNode messages=array();var rows=db.mysql.queryForList("SELECT message_no,role,content,run_no,created_time FROM (SELECT * FROM t_agent_message WHERE session_no=? ORDER BY id DESC LIMIT 100) m ORDER BY id",no);
        for(var row:rows){ObjectNode m=node(row).deepCopy();if("AGENT".equals(row.get("role"))&&row.get("run_no")!=null){var runs=db.mysql.queryForList("SELECT result_json FROM t_agent_run WHERE run_no=?",row.get("run_no"));if(!runs.isEmpty())m.set("result",read(runs.getFirst().get("result_json")));}messages.add(m);}result.set("messages",messages);return result;}
    public void close(AgentActor actor,String no){db.tx.executeWithoutResult(tx->{own(actor,no);db.mysql.queryForList("SELECT user_id FROM t_agent_user_gate WHERE user_id=? FOR UPDATE",actor.userId());db.mysql.queryForList("SELECT id FROM t_agent_session WHERE session_no=? FOR UPDATE",no);if(db.mysql.queryForObject("SELECT COUNT(*) FROM t_agent_run WHERE session_no=? AND status='RUNNING' AND created_time>DATE_SUB(now(3),INTERVAL 100 SECOND)",Integer.class,no)>0)throw error(409,"AGENT_BUSY","正在处理消息，请稍后关闭");db.mysql.update("UPDATE t_agent_session SET status='CLOSED',closed_time=now(3) WHERE session_no=? AND user_id=?",no,actor.userId());});}
    public Start start(AgentActor actor,String no,String clientId,String input,String requestHash){return db.tx.execute(tx->{
        own(actor,no);
        db.mysql.update("INSERT IGNORE INTO t_agent_user_gate(user_id) VALUES(?)",actor.userId());
        var gate=db.mysql.queryForMap("SELECT *,lease_until>now(3) AS busy FROM t_agent_user_gate WHERE user_id=? FOR UPDATE",actor.userId());
        if(!truth(gate.get("busy"))&&gate.get("lease_run_no")!=null)recoverExpired(gate.get("lease_run_no").toString());
        var s=db.mysql.queryForMap("SELECT *,last_active_time<DATE_SUB(now(3),INTERVAL 24 HOUR) AS expired FROM t_agent_session WHERE session_no=? AND user_id=? FOR UPDATE",no,actor.userId());
        var previous=db.mysql.queryForList("SELECT r.run_no,r.request_hash,r.result_json FROM t_agent_message m JOIN t_agent_run r ON r.run_no=m.run_no WHERE m.session_no=? AND m.client_message_id=?",no,clientId);
        if(!previous.isEmpty()){var p=previous.getFirst();if(!requestHash.equals(p.get("request_hash")))throw error(409,"AGENT_IDEMPOTENCY_CONFLICT","消息标识已被其他内容使用");if(p.get("result_json")==null)throw error(409,"AGENT_RUN_PENDING","原消息仍在处理或已中断，请查询会话状态");return new Start(p.get("run_no").toString(),s,read(p.get("result_json")));}
        if(!"ACTIVE".equals(s.get("status")))throw error(409,"AGENT_SESSION_INACTIVE","会话已结束或已转人工，请创建新会话");
        if(truth(s.get("expired")))throw error(409,"AGENT_SESSION_EXPIRED","会话已过期，请创建新会话");
        if(truth(gate.get("busy")))throw error(429,"AGENT_BUSY","你已有消息正在处理");
        int count=db.mysql.queryForObject("SELECT COUNT(*) FROM t_agent_run r JOIN t_agent_session s ON r.session_no=s.session_no WHERE s.user_id=? AND r.created_time>DATE_SUB(now(3),INTERVAL 1 MINUTE)",Integer.class,actor.userId());
        if(count>=5)throw error(429,"AGENT_RATE_LIMITED","每分钟最多发送 5 条消息，请稍后再试");
        String run=id("AR");db.mysql.update("UPDATE t_agent_user_gate SET lease_run_no=?,lease_until=TIMESTAMPADD(SECOND,?,now(3)) WHERE user_id=?",run,(props.getRunTimeoutMs()+999)/1000+10,actor.userId());
        db.mysql.update("INSERT INTO t_agent_run(run_no,session_no,status,trace_id,request_hash,prompt_version,provider,master_model_name,model_name) VALUES(?,?,'RUNNING',?,?,'customer-v2',?,?,?)",run,no,Objects.toString(api.context.TraceContext.getTraceId(),run),requestHash,props.getProvider(),props.getMasterModel(),props.getResponseModel());
        db.mysql.update("INSERT INTO t_agent_message(message_no,session_no,run_no,client_message_id,role,content) VALUES(?,?,?,?,'USER',?)",id("AM"),no,run,clientId,input);
        db.mysql.update("UPDATE t_agent_session SET last_active_time=now(3),message_count=message_count+1 WHERE session_no=?",no);
        return new Start(run,s,null);
    });}
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
    public JsonNode history(String session){var rows=db.mysql.queryForList("SELECT role,content FROM (SELECT id,role,content FROM t_agent_message WHERE session_no=? ORDER BY id DESC LIMIT 10) m ORDER BY id",session);return node(rows);}
    public void finish(AgentActor actor,String session,String run,ObjectNode master,ArrayNode results,ObjectNode response,ObjectNode slots,ArrayNode path,int tools,int inputTokens,int outputTokens,long elapsed,String state){
        db.tx.executeWithoutResult(tx->{var gate=db.mysql.queryForMap("SELECT lease_run_no FROM t_agent_user_gate WHERE user_id=? FOR UPDATE",actor.userId());if(!run.equals(gate.get("lease_run_no")))throw error(409,"AGENT_LEASE_LOST","运行已过期");
            int n=db.mysql.update("UPDATE t_agent_run SET status=?,error_code=?,master_result_json=?,specialist_results_json=?,result_json=?,graph_path_json=?,tool_call_count=?,tool_rounds=?,input_tokens=?,output_tokens=?,latency_ms=?,finished_time=now(3) WHERE run_no=? AND status='RUNNING'",state,response.path("errorCode").asText(null),write(master),write(results),write(response),write(path),tools,Math.min(tools,3),inputTokens,outputTokens,elapsed,run);
            if(n!=1)throw error(409,"AGENT_RUN_FINISHED","消息已处理");
            db.mysql.update("INSERT INTO t_agent_message(message_no,session_no,run_no,role,content,citations_json) VALUES(?,?,?,'AGENT',?,?)",id("AM"),session,run,response.path("answer").asText(),write(response.path("citations")));
            ArrayNode chunkIds=array();for(var c:response.path("citations"))if(c.has("chunkId"))chunkIds.add(c.path("chunkId"));
            db.mysql.update("UPDATE t_agent_run SET knowledge_chunk_ids_json=? WHERE run_no=?",write(chunkIds),run);
            db.mysql.update("UPDATE t_agent_session SET slot_json=?,last_intent=?,summary=?,message_count=message_count+1,last_active_time=now(3) WHERE session_no=?",write(slots),master.path("intents").path(0).path("code").asText("OTHER"),summary(master),session);
            db.mysql.update("UPDATE t_agent_user_gate SET lease_run_no=NULL,lease_until=NULL WHERE user_id=? AND lease_run_no=?",actor.userId(),run);
        });
    }
    private static String summary(ObjectNode master){StringBuilder b=new StringBuilder();for(var i:master.path("intents"))b.append(i.path("code").asText()).append(' ').append(i.path("slots")).append(';');return b.substring(0,Math.min(b.length(),800));}
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
    public void process(long admin,String no,boolean resolve,String resolution){int n=resolve?db.mysql.update("UPDATE t_agent_handoff SET status='RESOLVED',resolved_time=now(3),resolution=? WHERE handoff_no=? AND status='PROCESSING' AND handler_id=?",redact(resolution),no,admin):db.mysql.update("UPDATE t_agent_handoff SET status='PROCESSING',handler_id=?,claimed_time=now(3) WHERE handoff_no=? AND status='OPEN'",admin,no);if(n!=1)throw error(409,"AGENT_HANDOFF_CONFLICT","记录状态已变化或由其他客服处理");}
}
