package com.example.agent.customer;

import api.agent.*;
import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.StateGraph;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import org.apache.dubbo.config.annotation.DubboReference;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Supplier;
import java.util.function.Consumer;
import static com.alibaba.cloud.ai.graph.action.AsyncNodeAction.node_async;
import static com.example.agent.customer.CustomerJson.*;

@Service
public class CustomerRunner {
    private static final Logger log=LoggerFactory.getLogger(CustomerRunner.class);
    private static final ExecutorService STAGE_EXECUTOR=Executors.newVirtualThreadPerTaskExecutor();
    private record IndexedResult(int index,ObjectNode result) {}
    @DubboReference(timeout=3000,retries=0,check=false,url="${DUBBO_ORDER_SERVICE_URL:dubbo://127.0.0.1:20880}")
    private AgentOrderQueryDubboService orders;
    @DubboReference(timeout=3000,retries=0,check=false,url="${DUBBO_ITEM_QUERY_SERVICE_URL:dubbo://127.0.0.1:20880}")
    private AgentItemQueryDubboService items;
    private final CustomerStore store;private final CustomerMaster master;private final CustomerProperties props;
    private final CustomerModels models;private final CustomerKnowledgeService knowledge;private final IntentReleaseService releases;
    private final CompiledGraph graph;
    public CustomerRunner(CustomerStore store,CustomerMaster master,CustomerProperties props,CustomerModels models,CustomerKnowledgeService knowledge,IntentReleaseService releases){this.store=store;this.master=master;this.props=props;this.models=models;this.knowledge=knowledge;this.releases=releases;this.graph=compileGraph();}
    static final class Run {
        final AgentActor actor;final String session,no,question,traceId;final CustomerBudget budget;final JsonNode previous;final long startedNanos;
        final java.util.function.BiConsumer<String,String> stageSink;final Consumer<String> deltaSink;final Consumer<JsonNode> lifecycleSink;
        final AtomicInteger tools=new AtomicInteger(),input=new AtomicInteger(),output=new AtomicInteger();final AtomicLong ttftMs=new AtomicLong(-1);final AtomicBoolean active=new AtomicBoolean(true),embeddingCacheHit=new AtomicBoolean(false);
        final ArrayNode path=array(),results=array();final Object pathLock=new Object();ObjectNode classified=object(),response=object(),slots=object();String handoff;int clarifications;boolean clarificationShown,answerStreamed;ArrayNode queue=array();
        Run(AgentActor actor,String session,String no,String q,JsonNode previous,CustomerProperties props,long startedNanos,java.util.function.BiConsumer<String,String> stageSink,Consumer<String> deltaSink,Consumer<JsonNode> lifecycleSink){this.actor=actor;this.session=session;this.no=no;this.question=q;this.previous=previous;this.startedNanos=startedNanos;this.stageSink=stageSink;this.deltaSink=deltaSink;this.lifecycleSink=lifecycleSink;this.traceId=Objects.toString(api.context.TraceContext.getTraceId(),no);budget=new CustomerBudget(props.getRunTimeoutMs());clarifications=previous.path("clarificationAttempts").asInt();}
    }
    public JsonNode send(AgentActor actor,String session,String clientId,String raw){
        return send(actor,session,clientId,raw,(code,label)->{},ignored->{},ignored->{});
    }
    public JsonNode send(AgentActor actor,String session,String clientId,String raw,Consumer<String> stageSink){
        return send(actor,session,clientId,raw,(code,label)->stageSink.accept(label),ignored->{},ignored->{});
    }
    public JsonNode send(AgentActor actor,String session,String clientId,String raw,Consumer<String> stageSink,Consumer<String> deltaSink,Consumer<JsonNode> lifecycleSink){
        return send(actor,session,clientId,raw,(code,label)->stageSink.accept(label),deltaSink,lifecycleSink);
    }
    public JsonNode send(AgentActor actor,String session,String clientId,String raw,java.util.function.BiConsumer<String,String> stageSink,Consumer<String> deltaSink,Consumer<JsonNode> lifecycleSink){
        if(raw==null||raw.isBlank()||raw.codePointCount(0,raw.length())>2000||raw.chars().anyMatch(c->c==0)||clientId==null||!clientId.matches("[A-Za-z0-9_-]{1,64}"))throw error(400,"AGENT_MESSAGE_INVALID","消息须为 1–2000 个字符，并携带合法消息标识");
        long requestStarted=System.nanoTime();String question=redact(raw);var started=store.start(actor,session,clientId,question,hash(raw));
        if(started.replay()!=null){JsonNode replay=started.replay();notifyLifecycle(lifecycleSink,object().put("event","run.started").put("runNo",replay.path("runNo").asText()).put("traceId",replay.path("traceId").asText()).put("replayed",true));emitDeltas(deltaSink,replay.path("answer").asText());notifyLifecycle(lifecycleSink,object().put("event","run.completed").put("result",replay.deepCopy()));return replay;}
        JsonNode previous=read(started.session().get("slot_json"));
        if(previous.isObject())((ObjectNode)previous).put("summary",Objects.toString(started.session().get("summary"),"")).put("lastIntent",Objects.toString(started.session().get("last_intent"),""));
        Run run=new Run(actor,session,started.runNo(),question,previous,props,requestStarted,stageSink,deltaSink,lifecycleSink);String status="SUCCEEDED";
        notifyLifecycle(lifecycleSink,object().put("event","run.started").put("runNo",run.no).put("traceId",run.traceId));
        try{
            graph.invoke(Map.of("run",run));run.budget.remaining();
        }catch(Exception e){String code=failureCode(e);
            log.error("CustomerAgent failed: runNo={}, traceId={}, code={}, detail={}",run.no,run.traceId,code,failureDetail(e));
            status="AGENT_TIMEOUT".equals(code)?"TIMEOUT":"FAILED";run.response=object().put("schemaVersion",2).put("answer","暂时无法完成查询，请稍后重试，或发送“转人工”。").put("runNo",run.no).put("traceId",run.traceId).put("errorCode",code).put("handoffRequired",run.handoff!=null).put("handoffNo",run.handoff);run.response.set("citations",array());run.response.set("suggestedActions",array());run.response.set("intentResults",run.results);run.slots=run.previous.isObject()?(ObjectNode)run.previous.deepCopy():object();}
        finally{run.active.set(false);}
        if("SUCCEEDED".equals(status)&&!run.answerStreamed)emitDeltas(run,run.response.path("answer").asText());
        boolean cacheHit=run.embeddingCacheHit.get()||run.classified.path("recognition").path("embeddingCacheHit").asBoolean(false);Integer ttft=run.ttftMs.get()<0?null:(int)Math.min(Integer.MAX_VALUE,run.ttftMs.get());
        store.finish(actor,session,run.no,run.classified,run.results,run.response,run.slots,run.path,run.tools.get(),run.input.get(),run.output.get(),(System.nanoTime()-requestStarted)/1000000,status,cacheHit,ttft);
        if("SUCCEEDED".equals(status)){notifyLifecycle(lifecycleSink,object().put("event","run.completed").put("result",run.response.deepCopy()));}
        else notifyLifecycle(lifecycleSink,object().put("event","run.failed").put("runNo",run.no).put("errorCode",run.response.path("errorCode").asText("AGENT_DEPENDENCY_UNAVAILABLE")).put("answer",run.response.path("answer").asText()));
        return run.response;
    }
    private static void notifyLifecycle(Consumer<JsonNode> sink,JsonNode event){try{sink.accept(event);}catch(RuntimeException ignored){}}
    private static void emitDeltas(Consumer<String> sink,String answer){
        for(int offset=0;offset<answer.length();) {int count=Math.min(48,answer.codePointCount(offset,answer.length()));int end=answer.offsetByCodePoints(offset,count);String delta=answer.substring(offset,end);try{sink.accept(delta);}catch(RuntimeException ignored){}offset=end;}
    }
    private static void emitDeltas(Run run,String answer){
        for(int offset=0;offset<answer.length();) {int count=Math.min(48,answer.codePointCount(offset,answer.length()));int end=answer.offsetByCodePoints(offset,count);String delta=answer.substring(offset,end);run.ttftMs.compareAndSet(-1,Math.max(0,TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-run.startedNanos)));try{run.deltaSink.accept(delta);}catch(RuntimeException ignored){}offset=end;}
    }
    private CompiledGraph compileGraph() {
        try {
            StateGraph graph=new StateGraph();
            graph.addNode("INPUT_GUARD",node_async(state->{Run run=run(state);addPath(run,"INPUT_GUARD");stage(run,"INPUT_GUARD","正在接收问题…");run.budget.remaining();return graphState(run);}));
            graph.addNode("LOAD_CONTEXT",node_async(state->{Run run=run(state);addPath(run,"LOAD_CONTEXT");stage(run,"LOAD_CONTEXT","正在读取会话上下文…");return graphState(run);}));
            graph.addNode("MASTER_AGENT",node_async(state->{Run run=run(state);addPath(run,"MASTER_AGENT");stage(run,"MASTER_AGENT","正在识别问题…");run.classified=master.recognize(run.question,run.no,run.previous,store.history(run.session),run.budget,run.input,run.output);addPath(run,run.classified.path("recognition").path("source").asText());return graphState(run);}));
            graph.addNode("SLOT_RESOLVE",node_async(state->{Run run=run(state);addPath(run,"SLOT_RESOLVE");prepare(run);return graphState(run);}));
            graph.addNode("STAGE_DISPATCHER",node_async(state->{Run run=run(state);dispatchStages(run);return graphState(run);}));
            graph.addNode("RESPONSE_AGENT",node_async(state->{Run run=run(state);respond(run);return graphState(run);}));
            graph.addNode("OUTPUT_GUARD",node_async(state->{Run run=run(state);stage(run,"OUTPUT_GUARD","正在校验并整理答复…");guard(run);return graphState(run);}));
            graph.addEdge(StateGraph.START,"INPUT_GUARD").addEdge("INPUT_GUARD","LOAD_CONTEXT").addEdge("LOAD_CONTEXT","MASTER_AGENT").addEdge("MASTER_AGENT","SLOT_RESOLVE").addEdge("SLOT_RESOLVE","STAGE_DISPATCHER").addEdge("STAGE_DISPATCHER","RESPONSE_AGENT").addEdge("RESPONSE_AGENT","OUTPUT_GUARD").addEdge("OUTPUT_GUARD",StateGraph.END);
            return graph.compile();
        } catch(Exception e) { throw new IllegalStateException("Unable to compile CustomerAgent graph",e); }
    }
    private static Run run(OverAllState state) { return state.value("run",Run.class).orElseThrow(); }
    private static void addPath(Run run,String item){synchronized(run.pathLock){run.path.add(item);}}
    private static void stage(Run run,String stage,String label){try{run.stageSink.accept(stage,label);}catch(RuntimeException ignored){}}
    private static String stageName(String node){return switch(node){case "ORDER_AGENT"->"正在查询订单…";case "AFTER_SALE_AGENT"->"正在查询售后事实、发货凭证和规则…";case "RULE_AGENT"->"正在检索公开规则…";case "PRE_SALE_AGENT"->"正在查询商品信息和说明…";case "COMPLAINT_AGENT"->"正在记录并转交人工…";case "CLARIFY"->"正在确认必要信息…";default->"正在处理问题…";};}
    private static Map<String,Object> graphState(Run run) {
        return Map.of("run",run,"graphPath",run.path.deepCopy(),"classified",run.classified.deepCopy(),
            "pendingIntents",run.queue.deepCopy(),"intentResults",run.results.deepCopy(),"response",run.response.deepCopy());
    }
    private static String failureCode(Throwable error) {
        for(Throwable cause=error;cause!=null;cause=cause.getCause()) {
            if(cause instanceof com.example.agent.exception.RagApiException api)return api.errorCode();
            if(cause instanceof api.exception.BizException biz)return biz.getErrorCode();
        }
        return "AGENT_DEPENDENCY_UNAVAILABLE";
    }
    private String failureDetail(Throwable error) {
        Throwable cause=error;
        Set<Throwable> seen=Collections.newSetFromMap(new IdentityHashMap<>());
        while(cause.getCause()!=null && seen.add(cause.getCause()))cause=cause.getCause();
        String message=Objects.toString(cause.getMessage(),"");
        if(props.getApiKey()!=null && !props.getApiKey().isBlank())message=message.replace(props.getApiKey(),"[已隐藏]");
        String detail=cause.getClass().getSimpleName()+": "+redact(message)
            .replaceAll("\\bsk-[A-Za-z0-9_-]+","[已隐藏]").replace('\r',' ').replace('\n',' ');
        return detail.substring(0,Math.min(detail.length(),1000));
    }
    private void prepare(Run r) {
        String release=r.classified.path("recognition").path("releaseNo").asText();
        if("L2_RAG".equals(r.classified.path("recognition").path("source").asText())&&!r.budget.call(500,()->releases.isActive(release)))
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
    private void dispatchStages(Run r) {
        TreeMap<Integer,List<Integer>> stages=new TreeMap<>();
        for(int i=0;i<r.queue.size();i++)stages.computeIfAbsent(Math.max(1,r.queue.get(i).path("executionStage").asInt(1)),ignored->new ArrayList<>()).add(i);
        Map<Integer,ObjectNode> completed=new HashMap<>();ArrayNode priorStageFacts=array();
        for(var entry:stages.entrySet()) {
            if(entry.getKey()>1&&!priorStageFacts.isEmpty()) {
                ObjectNode stageContext=r.previous.isObject()?(ObjectNode)r.previous.deepCopy():object();
                ObjectNode focus=stageContext.with("activeFocus");
                for(JsonNode factNode:priorStageFacts)for(JsonNode ev:factNode.path("evidence")) {
                    if(ev.hasNonNull("orderNo"))focus.put("orderNo",ev.path("orderNo").asText());
                    if(ev.hasNonNull("disputeNo"))focus.put("disputeNo",ev.path("disputeNo").asText());
                    if(ev.hasNonNull("itemId"))focus.put("itemId",ev.path("itemId").asText());
                }
                stageContext.put("activeFocusTurn",stageContext.path("turnNo").asInt(0));
                for(int index:entry.getValue()) {
                    ObjectNode task=(ObjectNode)r.queue.get(index);
                    if("NEED_CLARIFICATION".equals(task.path("resolutionState").asText())) {
                        task.put("resolutionState","READY").putNull("clarifyQuestion");
                        CustomerMaster.resolve(task,stageContext,r.question);
                    }
                }
            }
            r.budget.remaining();addPath(r,"STAGE_"+entry.getKey());stage(r,"STAGE_EXECUTION","正在并行处理第 "+entry.getKey()+" 阶段的 "+entry.getValue().size()+" 项任务…");
            List<CompletableFuture<IndexedResult>> futures=new ArrayList<>();
            for(int index:entry.getValue()) {
                ObjectNode task=(ObjectNode)r.queue.get(index);String agent="NEED_CLARIFICATION".equals(task.path("resolutionState").asText())?"CLARIFY":IntentCatalog.ROUTES.getOrDefault(task.path("code").asText(),"FALLBACK");
                task.set("dependencyFacts",priorStageFacts.deepCopy());
                stage(r,agent,stageName(agent));
                futures.add(CompletableFuture.supplyAsync(()->new IndexedResult(index,executeTask(r,task,agent)),STAGE_EXECUTOR));
            }
            try {
                CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).get(r.budget.remaining(),TimeUnit.MILLISECONDS);
                for(var future:futures){var result=future.join();if(result.result()!=null)completed.put(result.index(),result.result());}
            } catch(InterruptedException e) {
                Thread.currentThread().interrupt();throw error(504,"AGENT_CANCELLED","请求已取消");
            } catch(ExecutionException|TimeoutException e) {
                futures.forEach(future->future.cancel(true));throw error(504,"AGENT_TIMEOUT","处理超时，请稍后重试或联系人工");
            }
            for(int index:entry.getValue()) {
                ObjectNode result=completed.get(index);if(result==null||!result.path("evidence").isArray()||result.path("evidence").isEmpty())continue;
                ObjectNode facts=object().put("code",result.path("code").asText()).put("intentInstanceId",result.path("intentInstanceId").asText());
                facts.set("evidence",result.path("evidence").deepCopy());for(String field:List.of("order","timeline","deliveryEvidenceSummary"))if(result.has(field))facts.set(field,result.path(field).deepCopy());priorStageFacts.add(facts);
            }
        }
        r.results.removeAll();for(int i=0;i<r.queue.size();i++){ObjectNode result=completed.get(i);if(result!=null)r.results.add(result);}
    }
    private void createHandoff(Run r,String reason) {
        synchronized(r) {
            if(r.handoff!=null)return;
            r.budget.remaining();CustomerToolPolicy.require("COMPLAINT_AGENT","createHandoff");
            int sequence=r.tools.incrementAndGet();if(sequence>6)throw error(429,"AGENT_TOOL_LIMIT","工具调用达到上限");
            long started=System.nanoTime();
            r.handoff=store.handoff(r.actor,r.session,r.no,reason,r.classified);
            store.tool(r.no,r.session,r.classified.path("intents").path(0).path("intentInstanceId").asText(),"COMPLAINT_AGENT","createHandoff",sequence,
                Map.of("reason",reason),Map.of("handoffNo",r.handoff),true,(System.nanoTime()-started)/1000000);
        }
    }
    private ObjectNode executeTask(Run r,ObjectNode task,String agent) {
        r.budget.remaining();String code=task.path("code").asText(),instance=task.path("intentInstanceId").asText();addPath(r,agent);
        ObjectNode result=object().put("intentInstanceId",instance).put("code",code).put("agentName",agent).put("resolutionState","COMPLETED");
        result.set("citations",array());result.set("evidence",array());
        try {
            if("CLARIFY".equals(agent)) {
                // Ask only one question. Other pending instances remain in session state.
                synchronized(r){if(r.clarificationShown)return null;r.clarificationShown=true;}
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
            else if(agent.equals("PRE_SALE_AGENT"))preSale(r,task,result);
            else policy(r,task,result,agent);
            CustomerOutputGuard.redactPayload(result);
            CustomerOutputGuard.validate(task,result);
        }catch(Exception e) {
            result.remove(List.of("bizNo","bizType","choices","order","timeline","policyAnswer"));
            result.set("citations",array());result.set("evidence",array());
            result.put("resolutionState","FAILED").put("errorCode",failureCode(e)).put("answer","这项信息暂时无法查询，请确认编号属于当前账号，或联系人工。");
        }
        task.put("resolutionState",result.path("resolutionState").asText());return result;
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
        String order=task.path("code").asText().equals("AFTER_SALE_STATUS_COMBINED")?task.path("slots").path("orderNo").asText():fact.path("orderNo").asText();
        if(task.path("code").asText().equals("AFTER_SALE_STATUS_COMBINED")&&!order.equals(fact.path("orderNo").asText()))throw error(400,"AGENT_SLOT_CONFLICT","订单与售后不对应");
        JsonNode orderFact=null;
        if(!order.isBlank()) {
            try {orderFact=tool(r,task,"queryOrder",Map.of("orderNo",order),()->orders.queryOrder(r.actor,order));result.set("order",orderFact);}
            catch(RuntimeException unavailable) {if("AFTER_SALE_STATUS_COMBINED".equals(task.path("code").asText()))throw unavailable;result.put("orderFactsUnavailable",true);}
            if(orderFact!=null)try {JsonNode delivery=tool(r,task,"queryDeliveryEvidenceSummary",Map.of("orderNo",order),()->orders.queryDeliveryEvidenceSummary(r.actor,order));result.set("deliveryEvidenceSummary",delivery);}
            catch(RuntimeException unavailable) {result.put("deliveryEvidenceUnavailable",true);}
        }
        result.put("bizType","DISPUTE").put("bizNo",no);result.set("evidence",array().add(fact));
        if(orderFact!=null)result.withArray("evidence").add(orderFact);
        ArrayNode sources=array();
        StringBuilder ruleQuery=new StringBuilder(retrievalQuestion(task)).append(" 售后状态 ").append(fact.path("status").asText(""));
        if(orderFact!=null)ruleQuery.append(" 订单状态 ").append(orderFact.path("orderStatus").asText(""))
            .append(" 交付状态 ").append(orderFact.path("deliveryStatus").asText(""));
        if(result.hasNonNull("deliveryEvidenceSummary"))ruleQuery.append(" 履约存证 ").append(CustomerContextWindowBuilder.clip(write(result.path("deliveryEvidenceSummary")),120));
        try { sources=searchKnowledge(r,task,"AFTER_SALE_AGENT",CustomerContextWindowBuilder.clip(ruleQuery.toString(),512)); }
        catch(RuntimeException unavailable) { result.put("policyUnavailable",true); }
        result.set("citations",sources);
        if(!sources.isEmpty())result.put("policyAnswer",true);
        JsonNode synthesisFacts=object().set("dispute",fact.deepCopy());
        ((ObjectNode)synthesisFacts).set("order",orderFact==null?NullNode.instance:orderFact.deepCopy());
        ((ObjectNode)synthesisFacts).set("deliveryEvidenceSummary",result.path("deliveryEvidenceSummary").deepCopy());
        String factAnswer="售后单 "+no+" 当前状态："+label(fact.path("status").asText())+"，最近更新时间："+fact.path("updatedTime").asText()+"。";
        result.put("answer",models.mock()?factAnswer+mockReference(sources):factAnswer);
        if(result.has("order")&&"AFTER_SALE_STATUS_COMBINED".equals(task.path("code").asText())) {
            if(!task.path("slots").path("orderNo").equals(orderFact.path("orderNo")))throw error(422,"AGENT_EVIDENCE_MISSING","订单事实不匹配");
        }
    }
    private void preSale(Run r,ObjectNode task,ObjectNode result) {
        JsonNode itemFact=NullNode.instance;
        String itemIdValue=task.path("slots").path("itemId").asText();
        if(itemIdValue.matches("\\d{1,10}")&&Long.parseLong(itemIdValue)>0) {
            long itemId=Long.parseLong(itemIdValue);
            itemFact=tool(r,task,"queryPublicItem",Map.of("itemId",itemId),()->items.queryPublicItem(itemId));
            result.set("evidence",array().add(itemFact));
        }
        String itemContext=itemFact.isNull()?"":" 商品 "+itemFact.path("itemName").asText("")+" "+itemFact.path("subTitle").asText("")+" 发货方式 "+itemFact.path("deliveryMode").asText("");
        ArrayNode sources=searchKnowledge(r,task,"PRE_SALE_AGENT",CustomerContextWindowBuilder.clip(retrievalQuestion(task)+itemContext,512));
        result.set("citations",sources);
        if(!sources.isEmpty())result.put("policyAnswer",true);
        ObjectNode facts=object();facts.set("product",itemFact);
        String factAnswer=itemFact.isNull()?"当前未找到可确认的商品资料，请查看商品详情页或联系人工。":"商品 "+itemFact.path("itemName").asText()+"（编号 "+itemFact.path("itemId").asText()+"）：价格 "+itemFact.path("price").asText()+"，可售库存 "+itemFact.path("availableStock").asText()+"。";
        result.put("answer",models.mock()?factAnswer+mockReference(sources):factAnswer);
    }
    private void policy(Run r,ObjectNode task,ObjectNode result,String agent){KnowledgeTool tool=new KnowledgeTool(r,task,agent);
        tool.search(retrievalQuestion(task),null,new ToolContext(Map.of("runNo",r.no,"sessionNo",r.session,"intentInstanceId",task.path("intentInstanceId").asText(),"agentName",agent)));
        ArrayNode sources=tool.sources;result.set("citations",sources);
        if(sources.isEmpty()){result.put("answer","当前未找到可确认的公开规则，请查看官方说明或联系人工。");return;}
        result.put("policyAnswer",true);result.put("answer",models.mock()?"根据公开说明："+mockReference(sources):"已检索到相关公开资料，请参考答复中的来源标注。");}
    private ArrayNode searchKnowledge(Run r,ObjectNode task,String agent,String query) {
        KnowledgeTool tool=new KnowledgeTool(r,task,agent);
        Map<String,Object> context=Map.of("runNo",r.no,"sessionNo",r.session,"intentInstanceId",task.path("intentInstanceId").asText(),"agentName",agent);
        tool.search(query,null,new ToolContext(context));
        return tool.sources;
    }
    private static String mockReference(JsonNode sources) {
        if(sources==null||!sources.isArray()||sources.isEmpty())return "";
        JsonNode source=sources.get(0);String content=source.path("content").asText("");
        return " "+content.substring(0,Math.min(240,content.length()))+" ["+source.path("sourceNo").asText("S1")+"]";
    }
    private static String retrievalQuestion(JsonNode task) {
        String question=task.path("rewrittenQuestion").asText(task.path("questionFragment").asText());
        JsonNode dependencies=task.path("dependencyFacts");if(!dependencies.isArray()||dependencies.isEmpty())return question;
        String prior=CustomerContextWindowBuilder.clip(write(dependencies),150);
        return CustomerContextWindowBuilder.clip(question,340)+" 已核实前序事实："+prior;
    }
    public final class KnowledgeTool {
        private final Run r;private final ObjectNode task;private final String agent;private boolean called;private ArrayNode sources=array();
        KnowledgeTool(Run r,ObjectNode task,String agent){this.r=r;this.task=task;this.agent=agent;}
        @Tool(name="searchKnowledge",description="检索公开平台规则、售后流程和商品说明；每个意图最多调用一次。")
        public synchronized JsonNode search(@ToolParam(description="检索问题") String query,@ToolParam(required=false,description="RULE/POLICY/AFTER_SALE/FAQ/PRODUCT") List<String> docTypes,ToolContext context){
            if(!r.no.equals(context.getContext().get("runNo"))||!task.path("intentInstanceId").asText().equals(context.getContext().get("intentInstanceId"))||!r.active.get())throw error(403,"TOOL_CONTEXT_MISSING","工具上下文不正确");
            if(!r.session.equals(context.getContext().get("sessionNo"))||!agent.equals(context.getContext().get("agentName")))throw error(403,"TOOL_CONTEXT_MISSING","工具上下文不正确");
            List<String> types=CustomerToolPolicy.documentTypes(agent,docTypes);
            if(called)throw error(429,"AGENT_TOOL_LIMIT","每个意图只能检索一次");called=true;int sequence=r.tools.incrementAndGet();if(sequence>6)throw error(429,"AGENT_TOOL_LIMIT","工具调用达到上限");long start=System.nanoTime();boolean success=false;String callNo=id("AT");
            try{CustomerKnowledgeService.SearchOutcome outcome=r.budget.call(props.getRetrievalTimeoutMs(),()->knowledge.searchWithMetadata(query,types,r.no,r.session,agent,callNo));r.budget.remaining();if(!r.active.get())throw error(504,"AGENT_CANCELLED","请求已结束");r.embeddingCacheHit.compareAndSet(false,outcome.embeddingCacheHit());sources=outcome.sources();for(var c:sources)((ObjectNode)c).put("intentInstanceId",task.path("intentInstanceId").asText());success=true;return sources;}
            finally{if(r.active.get())store.toolWithId(callNo,r.no,r.session,task.path("intentInstanceId").asText(),agent,"searchKnowledge",sequence,Map.of("query",redact(query)),sources,success,(System.nanoTime()-start)/1000000);}
        }
    }
    private static String label(String value){return switch(value){case "WAIT_PAY","INIT"->"待支付";case "PAID","SUCCESS"->"已支付";case "WAIT_DELIVERY"->"待交付";case "DELIVERED"->"已交付";case "VIEWED"->"已查看";case "CONFIRMED"->"已确认";case "FROZEN"->"已托管";case "SETTLE_PENDING","SETTLING"->"结算处理中";case "SETTLED"->"已结算";case "REFUNDING"->"退款处理中";case "REFUNDED"->"已退款";case "CANCELLED","CLOSED"->"已关闭";case "OPEN","PROCESSING"->"处理中";case "RESOLVED"->"已处理";case "NONE"->"无";default->value;};}
    private void respond(Run r){addPath(r,"RESPONSE_AGENT");ArrayNode pending=array(),citations=array(),actions=array();ObjectNode entities=r.previous.path("confirmedEntities").isObject()?(ObjectNode)r.previous.path("confirmedEntities").deepCopy():object();
        ObjectNode registry=r.previous.path("entityRegistry").isObject()?(ObjectNode)r.previous.path("entityRegistry").deepCopy():object();
        registry.withArray("orders");registry.withArray("disputes");registry.withArray("items");
        int turn=r.previous.path("turnNo").asInt(0)+1;String mentionedAt=java.time.Instant.now().toString();
        Set<String> current=new HashSet<>();r.classified.path("intents").forEach(i->current.add(i.path("intentInstanceId").asText()));
        for(var old:r.previous.path("pendingIntents"))if(!current.contains(old.path("intentInstanceId").asText()))pending.add(old.deepCopy());
        StringBuilder answer=new StringBuilder();ArrayNode answerFacts=array(),subQuestions=array();Map<String,String> citationIds=new LinkedHashMap<>();
        for(var t:r.classified.path("intents"))subQuestions.add(object().put("intentInstanceId",t.path("intentInstanceId").asText()).put("code",t.path("code").asText()).put("executionStage",t.path("executionStage").asInt(1)).put("question",t.path("rewrittenQuestion").asText(t.path("questionFragment").asText())).put("resolutionState",t.path("resolutionState").asText()));
        for(var result:r.results){
            ObjectNode facts=object().put("intentInstanceId",result.path("intentInstanceId").asText()).put("code",result.path("code").asText());
            for(String field:List.of("evidence","order","timeline","deliveryEvidenceSummary","choices"))if(result.has(field))facts.set(field,result.path(field).deepCopy());
            for(JsonNode task:r.classified.path("intents"))if(task.path("intentInstanceId").asText().equals(result.path("intentInstanceId").asText())&&task.path("dependencyFacts").isArray()&&!task.path("dependencyFacts").isEmpty())facts.set("priorStageFacts",task.path("dependencyFacts").deepCopy());
            answerFacts.add(facts);
            ArrayNode remapped=array();Map<String,String> localCitationIds=new HashMap<>();for(var source:result.path("citations")) {
                String identity=result.path("intentInstanceId").asText()+"|"+source.path("docNo").asText()+"|"+source.path("docVersion").asText()+"|"+source.path("chunkId").asText();
                String original=source.path("sourceNo").asText();String sourceNo=citationIds.computeIfAbsent(identity,ignored->"S"+(citationIds.size()+1));localCitationIds.put(original,sourceNo);ObjectNode copy=(ObjectNode)source.deepCopy();copy.put("sourceNo",sourceNo);remapped.add(copy);citations.add(copy.deepCopy());
            }
            if(models.mock()&&!localCitationIds.isEmpty()){String answerText=result.path("answer").asText();for(var mapping:localCitationIds.entrySet())answerText=answerText.replace("["+mapping.getKey()+"]","["+mapping.getValue()+"]");((ObjectNode)result).put("answer",answerText);}
            ((ObjectNode)result).set("citations",remapped);
            answer.append(result.path("answer").asText()).append('\n');
            if(result.hasNonNull("bizNo"))actions.add(object().put("intentInstanceId",result.path("intentInstanceId").asText()).put("type","DISPUTE".equals(result.path("bizType").asText())?"VIEW_AFTER_SALE":"VIEW_ORDER").put("target","#tradeSection").put("bizNo",result.path("bizNo").asText()));
        }
        for(var t:r.classified.path("intents")){if("NEED_CLARIFICATION".equals(t.path("resolutionState").asText()))pending.add(t.deepCopy());
            if("COMPLETED".equals(t.path("resolutionState").asText()))t.path("slots").fields().forEachRemaining(e->{
                String key=e.getKey(),value=e.getValue().asText();ArrayNode values=entities.withArray(key);ObjectNode old=null;
                for(var v:values)if((v.isTextual()?v.asText():v.path("value").asText()).equals(value)){if(v.isObject())old=(ObjectNode)v;break;}
                if(old==null){old=object().put("value",value);values.add(old);}
                old.put("confirmed",true).put("intentInstanceId",t.path("intentInstanceId").asText()).put("source",t.path("slotSources").path(key).asText()).put("lastMentionedTurn",turn).put("lastMentionedTime",mentionedAt);
                while(values.size()>10)values.remove(0);
                JsonNode matchingResult=null;for(JsonNode result:r.results)if(t.path("intentInstanceId").asText().equals(result.path("intentInstanceId").asText())){matchingResult=result;break;}
                if(key.equals("orderNo")){
                    JsonNode fact=matchingResult==null?MissingNode.getInstance():matchingResult.path("evidence").path(0);
                    registerEntity(registry.withArray("orders"),"orderNo",value,turn,mentionedAt,
                        fact.path("itemName").asText(fact.path("productName").asText("")),fact.path("orderStatus").asText(""),null);
                }else if(key.equals("disputeNo")){
                    JsonNode fact=matchingResult==null?MissingNode.getInstance():matchingResult.path("evidence").path(0);
                    String relatedOrder=matchingResult==null?"":matchingResult.path("order").path("orderNo").asText(fact.path("orderNo").asText(""));
                    registerEntity(registry.withArray("disputes"),"disputeNo",value,turn,mentionedAt,"",fact.path("status").asText(""),relatedOrder);
                }else if(key.equals("itemId")){
                    JsonNode fact=matchingResult==null?MissingNode.getInstance():matchingResult.path("evidence").path(0);
                    registerEntity(registry.withArray("items"),"itemId",value,turn,mentionedAt,fact.path("itemName").asText(""),"",null);
                }
            });}
        if(!pending.isEmpty()&&!r.clarificationShown)answer.append(pending.get(0).path("clarifyQuestion").asText()).append('\n');
        boolean abnormal=write(answerFacts).matches("(?s).*(FROZEN|MANUAL_REVIEW|ARBITRATING).*");
        if(!models.mock()&&(citations.size()>0||r.results.size()>1||abnormal)) {
            addPath(r,"RESPONSE_SYNTHESIZER");stage(r,"RESPONSE_SYNTHESIZER","正在根据已核实事实和规则整理答复…");
            String generated=models.synthesizeResponse(r.question,subQuestions,answerFacts,citations,r.budget,r.input,r.output,delta->{r.ttftMs.compareAndSet(-1,Math.max(0,TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-r.startedNanos)));r.answerStreamed=true;try{r.deltaSink.accept(delta);}catch(RuntimeException ignored){}});
            if(generated!=null&&!generated.isBlank()) {
                answer=new StringBuilder(generated.strip());
                if(r.results.size()==1)((ObjectNode)r.results.get(0)).put("answer",generated.strip());
            }
        }
        if(r.handoff!=null) {
            String handoffNotice="\n已转人工，记录编号："+r.handoff+"。";
            answer.append(handoffNotice);
            if(r.answerStreamed)try{r.deltaSink.accept(handoffNotice);}catch(RuntimeException ignored){}
        }
        r.response=object().put("schemaVersion",2).put("answer",answer.toString().strip()).put("runNo",r.no).put("traceId",r.traceId).put("handoffRequired",r.handoff!=null).put("handoffNo",r.handoff);
        r.response.set("intentResults",r.results);r.response.set("citations",citations);r.response.set("suggestedActions",actions);
        ObjectNode focus=r.previous.path("activeFocus").isObject()?(ObjectNode)r.previous.path("activeFocus").deepCopy():object();boolean focusUpdated=false;
        for(JsonNode task:r.classified.path("intents"))if("COMPLETED".equals(task.path("resolutionState").asText())){
            Iterator<Map.Entry<String,JsonNode>> fields=task.path("slots").fields();while(fields.hasNext()){var field=fields.next();focus.set(field.getKey(),field.getValue().deepCopy());focusUpdated=true;}
        }
        int focusTurn=focusUpdated?turn:r.previous.path("activeFocusTurn").asInt(turn);
        r.slots=object().put("schemaVersion",3).put("turnNo",turn).put("activeFocusTurn",focusTurn).put("clarificationAttempts",pending.isEmpty()?0:r.clarifications).put("clarifyingIntentInstanceId",pending.isEmpty()?null:clarifyingId(r,pending));r.slots.set("pendingIntents",pending);r.slots.set("confirmedEntities",entities);r.slots.set("activeFocus",focus);r.slots.set("entityRegistry",registry);
        if(r.handoff!=null)store.updateHandoffContext(r.handoff,r.no,r.classified,r.results,r.response);
    }
    private static void registerEntity(ArrayNode entries,String key,String value,int turn,String mentionedAt,String itemName,String status,String orderNo) {
        ObjectNode entry=null;int entryIndex=-1;for(int i=0;i<entries.size();i++){JsonNode existing=entries.get(i);if(value.equals(existing.path(key).asText(existing.path("value").asText("")))){entry=(ObjectNode)existing;entryIndex=i;break;}}
        if(entry==null){entry=object().put(key,value);entries.add(entry);}
        entry.put("lastMentionedTurn",turn).put("lastMentionedTime",mentionedAt).put("source","USER_INPUT").put("confirmed",true);
        if(!itemName.isBlank())entry.put("itemName",itemName);if(!status.isBlank())entry.put(key.equals("disputeNo")?"status":"orderStatus",status);
        if(orderNo!=null&&!orderNo.isBlank())entry.put("orderNo",orderNo);
        // Keep most recently active items at the end for bounded serialization and fallback recency.
        if(entryIndex>=0)entries.remove(entryIndex);entries.add(entry);while(entries.size()>10)entries.remove(0);
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
