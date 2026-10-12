package com.example.agent.customer;

import api.agent.*;
import com.alibaba.cloud.ai.graph.StateGraph;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import org.apache.dubbo.config.annotation.DubboReference;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Service;
import java.util.*;
import java.util.concurrent.atomic.*;
import java.util.function.Supplier;
import static com.alibaba.cloud.ai.graph.action.AsyncNodeAction.node_async;
import static com.alibaba.cloud.ai.graph.action.AsyncEdgeAction.edge_async;
import static com.example.agent.customer.CustomerJson.*;

@Service
public class CustomerRunner {
    @DubboReference(timeout=3000,retries=0,check=false)
    private AgentOrderQueryDubboService orders;
    private final CustomerStore store;private final CustomerMaster master;private final CustomerProperties props;
    private final CustomerModels models;private final CustomerKnowledgeService knowledge;private final IntentReleaseService releases;
    public CustomerRunner(CustomerStore store,CustomerMaster master,CustomerProperties props,CustomerModels models,CustomerKnowledgeService knowledge,IntentReleaseService releases){this.store=store;this.master=master;this.props=props;this.models=models;this.knowledge=knowledge;this.releases=releases;}
    static final class Run {
        final AgentActor actor;final String session,no,question,traceId;final CustomerBudget budget;final JsonNode previous;
        final AtomicInteger tools=new AtomicInteger(),input=new AtomicInteger(),output=new AtomicInteger();final AtomicBoolean active=new AtomicBoolean(true);
        final ArrayNode path=array(),results=array();ObjectNode classified=object(),response=object(),slots=object();String handoff;int clarifications;int next;boolean clarificationShown;ArrayNode queue=array();
        Run(AgentActor actor,String session,String no,String q,JsonNode previous,CustomerProperties props){this.actor=actor;this.session=session;this.no=no;this.question=q;this.previous=previous;this.traceId=Objects.toString(api.context.TraceContext.getTraceId(),no);budget=new CustomerBudget(props.getRunTimeoutMs());clarifications=previous.path("clarificationAttempts").asInt();}
    }
    public JsonNode send(AgentActor actor,String session,String clientId,String raw){
        if(raw==null||raw.isBlank()||raw.codePointCount(0,raw.length())>2000||raw.chars().anyMatch(c->c==0)||clientId==null||!clientId.matches("[A-Za-z0-9_-]{1,64}"))throw error(400,"AGENT_MESSAGE_INVALID","消息须为 1–2000 个字符，并携带合法消息标识");
        String question=redact(raw);var started=store.start(actor,session,clientId,question,hash(raw));if(started.replay()!=null)return started.replay();
        JsonNode previous=read(started.session().get("slot_json"));
        if(previous.isObject())((ObjectNode)previous).put("summary",Objects.toString(started.session().get("summary"),"")).put("lastIntent",Objects.toString(started.session().get("last_intent"),""));
        Run run=new Run(actor,session,started.runNo(),question,previous,props);long start=System.nanoTime();String status="SUCCEEDED";
        try{
            StateGraph graph=new StateGraph();
            graph.addNode("INPUT_GUARD",node_async(state->{run.path.add("INPUT_GUARD");run.budget.remaining();return Map.of();}));
            graph.addNode("MASTER_AGENT",node_async(state->{run.path.add("MASTER_AGENT");run.classified=master.recognize(question,run.no,run.previous,store.history(session),run.budget,run.input,run.output);run.path.add(run.classified.path("recognition").path("source").asText());return Map.of();}));
            graph.addNode("LOAD_CONTEXT",node_async(state->{run.path.add("LOAD_CONTEXT");return Map.of();}));
            graph.addNode("SLOT_RESOLVE",node_async(state->{run.path.add("SLOT_RESOLVE");prepare(run);return Map.of();}));
            graph.addNode("ROUTE",node_async(state->{run.path.add("ROUTE");run.budget.remaining();return Map.of();}));
            Map<String,String> routes=new HashMap<>();
            for(String name:List.of("ORDER_AGENT","AFTER_SALE_AGENT","RULE_AGENT","COMPLAINT_AGENT","PRE_SALE_AGENT","TEMPLATE_RESPONSE","CLARIFY","FALLBACK")) {
                routes.put(name,name);
                graph.addNode(name,node_async(state->{executeNext(run,name);return Map.of();}));
                graph.addEdge(name,"ROUTE");
            }
            routes.put("RESPONSE_AGENT","RESPONSE_AGENT");
            graph.addConditionalEdges("ROUTE",edge_async(state->nextNode(run)),routes);
            graph.addNode("RESPONSE_AGENT",node_async(state->{respond(run);return Map.of();}));
            graph.addNode("OUTPUT_GUARD",node_async(state->{guard(run);return Map.of();}));
            graph.addEdge(StateGraph.START,"INPUT_GUARD").addEdge("INPUT_GUARD","LOAD_CONTEXT").addEdge("LOAD_CONTEXT","MASTER_AGENT").addEdge("MASTER_AGENT","SLOT_RESOLVE").addEdge("SLOT_RESOLVE","ROUTE").addEdge("RESPONSE_AGENT","OUTPUT_GUARD").addEdge("OUTPUT_GUARD",StateGraph.END);
            graph.compile().invoke(Map.of());run.budget.remaining();
        }catch(Exception e){String code=failureCode(e);status="AGENT_TIMEOUT".equals(code)?"TIMEOUT":"FAILED";run.response=object().put("schemaVersion",2).put("answer","暂时无法完成查询，请稍后重试，或发送“转人工”。").put("runNo",run.no).put("traceId",run.traceId).put("errorCode",code).put("handoffRequired",run.handoff!=null).put("handoffNo",run.handoff);run.response.set("citations",array());run.response.set("suggestedActions",array());run.response.set("intentResults",run.results);run.slots=run.previous.isObject()?(ObjectNode)run.previous.deepCopy():object();}
        finally{run.active.set(false);}
        store.finish(actor,session,run.no,run.classified,run.results,run.response,run.slots,run.path,run.tools.get(),run.input.get(),run.output.get(),(System.nanoTime()-start)/1000000,status);
        return run.response;
    }
    private static String failureCode(Throwable error) {
        for(Throwable cause=error;cause!=null;cause=cause.getCause()) {
            if(cause instanceof com.example.agent.exception.RagApiException api)return api.errorCode();
            if(cause instanceof api.exception.BizException biz)return biz.getErrorCode();
        }
        return "AGENT_DEPENDENCY_UNAVAILABLE";
    }
    private void prepare(Run r) {
        String release=r.classified.path("recognition").path("releaseNo").asText();
        if("L2_RAG".equals(r.classified.path("recognition").path("source").asText())&&!releases.ready(release))
            throw error(503,"INTENT_RELEASE_REVOKED","意图库已撤销");
        boolean failedReply="CONTEXT_RESUME".equals(r.classified.path("recognition").path("source").asText())
            && "NEED_CLARIFICATION".equals(r.classified.path("intents").path(0).path("resolutionState").asText());
        if(failedReply)r.clarifications++;else r.clarifications=0;
        boolean escalation=r.classified.path("needHuman").asBoolean()||r.clarifications>=2;
        if(escalation)createHandoff(r,"用户请求、风险升级或连续澄清失败");
        int business=0;
        for(var value:r.classified.path("intents")) {
            if("COMPLETED".equals(value.path("resolutionState").asText()))continue;
            boolean complaint="COMPLAINT_AGENT".equals(IntentCatalog.ROUTES.get(value.path("code").asText()));
            if(!complaint && business++ >= (escalation?1:props.getMaxIntents()))continue;
            r.queue.add(value);
        }
    }
    private String nextNode(Run r) {
        if(r.next>=r.queue.size())return "RESPONSE_AGENT";
        JsonNode task=r.queue.get(r.next);
        return "NEED_CLARIFICATION".equals(task.path("resolutionState").asText())?"CLARIFY":IntentCatalog.ROUTES.getOrDefault(task.path("code").asText(),"FALLBACK");
    }
    private void createHandoff(Run r,String reason) {
        if(r.handoff!=null)return;
        r.budget.remaining();CustomerToolPolicy.require("COMPLAINT_AGENT","createHandoff");
        int sequence=r.tools.incrementAndGet();if(sequence>6)throw error(429,"AGENT_TOOL_LIMIT","工具调用达到上限");
        long started=System.nanoTime();
        r.handoff=store.handoff(r.actor,r.session,r.no,reason,r.classified);
        store.tool(r.no,r.session,r.classified.path("intents").path(0).path("intentInstanceId").asText(),"COMPLAINT_AGENT","createHandoff",sequence,
            Map.of("reason",reason),Map.of("handoffNo",r.handoff),true,(System.nanoTime()-started)/1000000);
    }
    private void executeNext(Run r,String agent) {
        ObjectNode task=(ObjectNode)r.queue.get(r.next++);
        r.budget.remaining();String code=task.path("code").asText(),instance=task.path("intentInstanceId").asText();r.path.add(agent);
        ObjectNode result=object().put("intentInstanceId",instance).put("code",code).put("agentName",agent).put("resolutionState","COMPLETED");
        result.set("citations",array());result.set("evidence",array());
        try {
            if("CLARIFY".equals(agent)) {
                // Ask only one question. Other pending instances remain in session state.
                if(r.clarificationShown)return;
                r.clarificationShown=true;
                result.put("resolutionState","NEED_CLARIFICATION").put("answer",task.path("clarifyQuestion").asText());
                if(task.path("missingSlots").toString().contains("orderNo")) {
                    try { result.set("choices",tool(r,task,"queryMyOrders",Map.of("limit",5),()->orders.queryMyOrders(r.actor,5))); }
                    catch(RuntimeException unavailable) { result.put("choicesUnavailable",true); }
                }
            }else if("COMPLAINT_AGENT".equals(agent)) {
                createHandoff(r,"用户要求投诉或人工客服");result.put("answer","已记录你的诉求并转交人工客服。");
            }else if(code.equals("GREETING"))result.put("answer","你好，我是平台智能客服，请问有什么可以帮你？");
            else if(code.equals("BOT_IDENTITY"))result.put("answer","我是平台智能客服，可以帮你查询订单、售后进度和平台规则，也可以协助转人工。");
            else if(code.equals("OTHER"))result.put("answer","请说明你想咨询的订单、售后或平台规则问题，也可以发送“转人工”。");
            else if(code.startsWith("PRE_SALE")&&!props.isPreSaleEnabled())result.put("answer","售前咨询暂未开放，请查看商品详情或联系人工。");
            else if(agent.equals("ORDER_AGENT"))order(r,task,result);
            else if(code.equals("AFTER_SALE_PROGRESS")||code.equals("AFTER_SALE_STATUS_COMBINED"))dispute(r,task,result);
            else policy(r,task,result,agent);
            CustomerOutputGuard.redactPayload(result);
            CustomerOutputGuard.validate(task,result);
        }catch(Exception e) {
            result.remove(List.of("bizNo","bizType","choices","order","timeline","policyAnswer"));
            result.set("citations",array());result.set("evidence",array());
            result.put("resolutionState","FAILED").put("errorCode",failureCode(e)).put("answer","这项信息暂时无法查询，请确认编号属于当前账号，或联系人工。");
        }
        task.put("resolutionState",result.path("resolutionState").asText());r.results.add(result);
    }
    private JsonNode tool(Run r,ObjectNode task,String name,Object args,Supplier<?> work){
        String agent="queryMyOrders".equals(name)&&"NEED_CLARIFICATION".equals(task.path("resolutionState").asText())?"MASTER_AGENT":IntentCatalog.ROUTES.get(task.path("code").asText());
        CustomerToolPolicy.require(agent,name);
        if(!r.active.get())throw error(504,"AGENT_CANCELLED","请求已结束");int seq=r.tools.incrementAndGet();if(seq>6)throw error(429,"AGENT_TOOL_LIMIT","工具调用达到上限");
        long start=System.nanoTime();JsonNode result=object();boolean ok=false;
        try{result=node(r.budget.call(3000,work::get));if(result==null||result.isNull())throw error(503,"AGENT_TOOL_EMPTY","工具没有返回事实");CustomerOutputGuard.redactPayload(result);ok=true;return result;}
        finally{if(r.active.get())store.tool(r.no,r.session,task.path("intentInstanceId").asText(),agent,name,seq,args,result,ok,(System.nanoTime()-start)/1000000);}
    }
    private void order(Run r,ObjectNode task,ObjectNode result){String code=task.path("code").asText(),no=task.path("slots").path("orderNo").asText();
        if(code.equals("ORDER_SEARCH")){JsonNode choices=tool(r,task,"queryMyOrders",Map.of("limit",5),()->orders.queryMyOrders(r.actor,5));result.set("choices",choices);result.put("answer",choices.isEmpty()?"没有找到你的订单。":"以下是你的最近订单，请选择要查询的订单。");return;}
        JsonNode fact=tool(r,task,"queryOrder",Map.of("orderNo",no),()->orders.queryOrder(r.actor,no));
        result.put("bizType","ORDER").put("bizNo",no);result.set("evidence",array().add(fact));
        String answer="订单 "+no+"：订单状态 "+label(fact.path("orderStatus").asText())+"，支付 "+label(fact.path("payStatus").asText())+"，交付 "+label(fact.path("deliveryStatus").asText())+"，托管 "+label(fact.path("escrowStatus").asText())+"。";
        if(code.equals("SETTLEMENT"))answer+=fact.path("settleAvailableTime").isNull()?"当前没有可确认的结算时间。":"系统记录的可结算时间为 "+fact.path("settleAvailableTime").asText()+"，实际结果以订单状态为准。";
        if(code.equals("ORDER_TIMELINE")){JsonNode timeline=tool(r,task,"queryOrderTimeline",Map.of("orderNo",no),()->orders.queryOrderTimeline(r.actor,no));result.set("timeline",timeline);answer+="下方展示该订单的状态记录。";}
        result.put("answer",answer);
    }
    private void dispute(Run r,ObjectNode task,ObjectNode result){String no=task.path("slots").path("disputeNo").asText();JsonNode fact=tool(r,task,"queryDispute",Map.of("disputeNo",no),()->orders.queryDispute(r.actor,no));
        if(task.path("code").asText().equals("AFTER_SALE_STATUS_COMBINED")){String order=task.path("slots").path("orderNo").asText();if(!order.equals(fact.path("orderNo").asText()))throw error(400,"AGENT_SLOT_CONFLICT","订单与售后不对应");JsonNode orderFact=tool(r,task,"queryOrder",Map.of("orderNo",order),()->orders.queryOrder(r.actor,order));result.set("order",orderFact);}
        result.put("bizType","DISPUTE").put("bizNo",no).put("answer","售后单 "+no+" 当前状态："+label(fact.path("status").asText())+"，最近更新时间："+fact.path("updatedTime").asText()+"。");result.set("evidence",array().add(fact));
        if(result.has("order")) {
            JsonNode orderFact=result.path("order");
            if(!task.path("slots").path("orderNo").equals(orderFact.path("orderNo")))throw error(422,"AGENT_EVIDENCE_MISSING","订单事实不匹配");
            result.withArray("evidence").add(orderFact);
            result.put("answer",result.path("answer").asText()+"订单 "+orderFact.path("orderNo").asText()+" 当前状态："+label(orderFact.path("orderStatus").asText())+"，交付："+label(orderFact.path("deliveryStatus").asText())+"。");
        }
    }
    private void policy(Run r,ObjectNode task,ObjectNode result,String agent){KnowledgeTool tool=new KnowledgeTool(r,task,agent);
        Map<String,Object> context=Map.of("runNo",r.no,"sessionNo",r.session,"intentInstanceId",task.path("intentInstanceId").asText(),"agentName",agent);
        if(models.mock())tool.search(task.path("rewrittenQuestion").asText(),null,new ToolContext(context));
        else models.retrieve(task.path("rewrittenQuestion").asText(),tool,Map.of("runNo",r.no,"sessionNo",r.session,"intentInstanceId",task.path("intentInstanceId").asText(),"agentName",agent),r.budget,r.input,r.output);
        ArrayNode sources=tool.sources;result.set("citations",sources);
        if(sources.isEmpty()){result.put("answer","当前未找到可确认的公开规则，请查看官方说明或联系人工。");return;}
        result.put("policyAnswer",true);StringBuilder answer=new StringBuilder("可参考以下公开说明：\n");for(var source:sources)answer.append('[').append(source.path("sourceNo").asText()).append("] ").append(source.path("content").asText()).append('\n');result.put("answer",answer.toString());}
    public final class KnowledgeTool {
        private final Run r;private final ObjectNode task;private final String agent;private boolean called;private ArrayNode sources=array();
        KnowledgeTool(Run r,ObjectNode task,String agent){this.r=r;this.task=task;this.agent=agent;}
        @Tool(name="searchKnowledge",description="检索公开平台规则、售后流程和商品说明；每个意图最多调用一次。")
        public synchronized JsonNode search(@ToolParam(description="检索问题") String query,@ToolParam(required=false,description="RULE/POLICY/AFTER_SALE/FAQ/PRODUCT") List<String> docTypes,ToolContext context){
            if(!r.no.equals(context.getContext().get("runNo"))||!task.path("intentInstanceId").asText().equals(context.getContext().get("intentInstanceId"))||!r.active.get())throw error(403,"TOOL_CONTEXT_MISSING","工具上下文不正确");
            if(!r.session.equals(context.getContext().get("sessionNo"))||!agent.equals(context.getContext().get("agentName")))throw error(403,"TOOL_CONTEXT_MISSING","工具上下文不正确");
            List<String> types=CustomerToolPolicy.documentTypes(agent,docTypes);
            if(called)throw error(429,"AGENT_TOOL_LIMIT","每个意图只能检索一次");called=true;int sequence=r.tools.incrementAndGet();if(sequence>6)throw error(429,"AGENT_TOOL_LIMIT","工具调用达到上限");long start=System.nanoTime();boolean success=false;String callNo=id("AT");
            try{ArrayNode found=r.budget.call(props.getRetrievalTimeoutMs(),()->knowledge.search(query,types,r.no,r.session,agent,callNo));r.budget.remaining();if(!r.active.get())throw error(504,"AGENT_CANCELLED","请求已结束");sources=found;for(var c:sources)((ObjectNode)c).put("intentInstanceId",task.path("intentInstanceId").asText());success=true;return sources;}
            finally{if(r.active.get())store.toolWithId(callNo,r.no,r.session,task.path("intentInstanceId").asText(),agent,"searchKnowledge",sequence,Map.of("query",redact(query)),sources,success,(System.nanoTime()-start)/1000000);}
        }
    }
    private static String label(String value){return switch(value){case "WAIT_PAY","INIT"->"待支付";case "PAID","SUCCESS"->"已支付";case "WAIT_DELIVERY"->"待交付";case "DELIVERED"->"已交付";case "VIEWED"->"已查看";case "CONFIRMED"->"已确认";case "FROZEN"->"已托管";case "SETTLE_PENDING","SETTLING"->"结算处理中";case "SETTLED"->"已结算";case "REFUNDING"->"退款处理中";case "REFUNDED"->"已退款";case "CANCELLED","CLOSED"->"已关闭";case "OPEN","PROCESSING"->"处理中";case "RESOLVED"->"已处理";case "NONE"->"无";default->value;};}
    private void respond(Run r){r.path.add("RESPONSE_AGENT");ArrayNode pending=array(),citations=array(),actions=array();ObjectNode entities=r.previous.path("confirmedEntities").isObject()?(ObjectNode)r.previous.path("confirmedEntities").deepCopy():object();
        if("CONTEXT_RESUME".equals(r.classified.path("recognition").path("source").asText())) {
            Set<String> current=new HashSet<>();r.classified.path("intents").forEach(i->current.add(i.path("intentInstanceId").asText()));
            for(var old:r.previous.path("pendingIntents"))if(!current.contains(old.path("intentInstanceId").asText()))pending.add(old.deepCopy());
        }
        StringBuilder answer=new StringBuilder();
        for(var result:r.results){answer.append(result.path("answer").asText()).append('\n');for(var c:result.path("citations"))citations.add(c);if(result.hasNonNull("bizNo"))actions.add(object().put("intentInstanceId",result.path("intentInstanceId").asText()).put("type","DISPUTE".equals(result.path("bizType").asText())?"VIEW_AFTER_SALE":"VIEW_ORDER").put("target","#tradeSection").put("bizNo",result.path("bizNo").asText()));}
        for(var t:r.classified.path("intents")){if("NEED_CLARIFICATION".equals(t.path("resolutionState").asText()))pending.add(t.deepCopy());
            if("COMPLETED".equals(t.path("resolutionState").asText()))t.path("slots").fields().forEachRemaining(e->{ArrayNode values=entities.withArray(e.getKey());boolean found=false;for(var v:values)if((v.isTextual()?v:v.path("value")).equals(e.getValue()))found=true;
                if(!found)values.add(object().<ObjectNode>set("value",e.getValue()).put("confirmed",true).put("intentInstanceId",t.path("intentInstanceId").asText()).put("source",t.path("slotSources").path(e.getKey()).asText()));
                while(values.size()>10)values.remove(0);});}
        if(!pending.isEmpty()&&!r.clarificationShown)answer.append(pending.get(0).path("clarifyQuestion").asText()).append('\n');
        if(r.handoff!=null)answer.append("已转人工，记录编号：").append(r.handoff).append('。');
        r.response=object().put("schemaVersion",2).put("answer",answer.toString().strip()).put("runNo",r.no).put("traceId",r.traceId).put("handoffRequired",r.handoff!=null).put("handoffNo",r.handoff);
        r.response.set("intentResults",r.results);r.response.set("citations",citations);r.response.set("suggestedActions",actions);
        r.slots=object().put("schemaVersion",2).put("clarificationAttempts",pending.isEmpty()?0:r.clarifications).put("clarifyingIntentInstanceId",pending.isEmpty()?null:clarifyingId(r,pending));r.slots.set("pendingIntents",pending);r.slots.set("confirmedEntities",entities);
        if(r.handoff!=null)store.updateHandoffContext(r.handoff,r.no,r.classified,r.results,r.response);
    }
    private String clarifyingId(Run r,ArrayNode pending) {
        for(var result:r.results)if("NEED_CLARIFICATION".equals(result.path("resolutionState").asText()))return result.path("intentInstanceId").asText();
        return pending.get(0).path("intentInstanceId").asText();
    }
    private void guard(Run r){r.path.add("OUTPUT_GUARD");r.budget.remaining();r.response.put("answer",redact(r.response.path("answer").asText()));
        Set<String> ids=new HashSet<>();for(var t:r.classified.path("intents"))ids.add(t.path("intentInstanceId").asText());for(var v:r.results)if(!ids.contains(v.path("intentInstanceId").asText()))throw error(422,"AGENT_OUTPUT_INVALID","响应归属不正确");
        if(!CustomerOutputGuard.safePayload(r.response))throw error(422,"AGENT_OUTPUT_UNSAFE","响应安全校验失败");
        if(r.response.path("answer").asText().isBlank())throw error(422,"AGENT_OUTPUT_INVALID","响应为空");}
}
