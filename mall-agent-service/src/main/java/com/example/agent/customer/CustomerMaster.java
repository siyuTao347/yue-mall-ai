package com.example.agent.customer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import org.springframework.stereotype.Service;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;
import static com.example.agent.customer.CustomerJson.*;

@Service
public class CustomerMaster {
    static final Pattern GREETING=Pattern.compile("(?:你好呀?|您好|嗨|哈喽|hello|hi|早上好|上午好|中午好|下午好|晚上好|早安|晚安)[!！?？。.,，、～~\\s]*");
    static final Pattern IDENTITY=Pattern.compile("(?:你是谁|你是什么|你是机器人吗|你是真人吗|你叫什么名字|who are you)[!！?？。.,，、～~\\s]*");
    static final Pattern ORDER=Pattern.compile("(?i)(?<![a-z0-9])(?:TR|T)\\d{6,40}(?![a-z0-9])");
    static final Pattern DISPUTE=Pattern.compile("(?i)(?<![a-z0-9])(?:DP|D)\\d{6,40}(?![a-z0-9])");
    static final Pattern ITEM_ID=Pattern.compile("(?i)(?:商品(?:编号|ID)?\\s*[:：#]?\\s*|item\\s*#?\\s*)(\\d{3,18})");
    private final CustomerProperties props; private final IntentReleaseService releases; private final CustomerModels models;
    public CustomerMaster(CustomerProperties props,IntentReleaseService releases,CustomerModels models){this.props=props;this.releases=releases;this.models=models;}
    static String l1(String text,int limit){String q=normalize(text);if(q.codePointCount(0,q.length())>limit)return null;if(GREETING.matcher(q).matches())return "GREETING";if(IDENTITY.matcher(q).matches())return "BOT_IDENTITY";return null;}
    static boolean human(String q) {
        if(q.matches("(?s).*(被盗|资金损失|赔偿|仲裁).*"))return true;
        String intent=q.replaceAll("(?:不要|不用|不需要|别).{0,3}(?:转人工|人工客服|投诉)", "");
        return intent.matches("(?s).*(转人工|人工客服|找真人|投诉).*");
    }
    static boolean contextDependent(String q){return q.matches("(?s).*(那个|这个|上次|之前|刚才|还没|第二个|第一个|再查|另外|并且|以及|同时|和|还有|[，,；;]).*")||ORDER.matcher(q).results().count()>1||q.matches("(?s).*(退款|退货).*(投诉|订单).*|.*订单.*(退款|退货).*");}
    public ObjectNode recognize(String question,String run,JsonNode context,JsonNode history,CustomerBudget budget,AtomicInteger in,AtomicInteger out){
        // Escalation must work even when the model and the intent database are unavailable.
        if(human(question)) {
            ObjectNode result=object().put("schemaVersion",2).put("originalQuestion",question).put("needHuman",true)
                .put("riskLevel",question.matches("(?s).*(被盗|资金损失|赔偿|仲裁).*")?"HIGH":"NORMAL").put("sentiment","NEGATIVE");
            result.set("intents",array().add(task("HUMAN_HELP",question,run+":i1",null)));
            result.set("recognition",object().put("source","ESCALATION_RULE"));return result;
        }
        ObjectNode resumed=resume(question,context);if(resumed!=null)return resumed;
        long recognitionStarted=System.nanoTime();int initialInput=in.get(),initialOutput=out.get();
        boolean pending=context.path("pendingIntents").size()>0;
        String simple=pending?null:l1(question,props.getL1MaxChars());ObjectNode recognition;
        ArrayNode intents=array(); boolean human=human(question);
        if(simple!=null){recognition=object().put("source","L1_RULE").put("ruleId",simple.equals("GREETING")?"greeting-v1":"identity-v1");intents.add(task(simple,question,run+":i1",null));}
        else {
            recognition=(pending||contextDependent(question)||human)?IntentReleaseService.fallback("CONTEXT_MULTI_INTENT_OR_ESCALATION"):releases.recognize(question,budget);
            if("L2_RAG".equals(recognition.path("source").asText())){ObjectNode t=task(recognition.path("code").asText(),question,run+":i1",null);t.put("similarity",recognition.path("similarity").asDouble());t.set("requiredSlots",recognition.path("requiredSlots"));intents.add(t);}
            else {
                JsonNode output=null;
                if(models.mock())output=mock(question,context);
                else for(int attempt=0;attempt<2;attempt++){try{output=read(models.classify(question,write(Map.of("history",history,"pending",context)),budget,in,out));validateModel(output,question);break;}catch(RuntimeException e){output=null;if(attempt==1)throw e;}}
                if(output==null)throw error(503,"AGENT_MODEL_EMPTY","暂时无法识别问题");
                int n=0;for(JsonNode candidate:output.path("intents")){if(n>=props.getMaxIntents())break;String code=candidate.path("code").asText();String fragment=candidate.path("questionFragment").asText(question);
                    ObjectNode t=task(code,fragment,run+":i"+(++n),candidate.path("confidence").asDouble(0));
                    if(candidate.path("rewrittenQuestion").isTextual())t.put("rewrittenQuestion",candidate.path("rewrittenQuestion").asText());
                    // Accept only explicitly grounded fields from the model. Session candidates are handled separately.
                    candidate.path("slots").fields().forEachRemaining(e->{if(Set.of("orderNo","disputeNo","itemId").contains(e.getKey())&&!e.getValue().isNull()&&fragment.contains(e.getValue().asText()))((ObjectNode)t.get("slots")).put(e.getKey(),e.getValue().asText());});
                    intents.add(t);}
                human|=output.path("needHuman").asBoolean(false)||Set.of("HIGH","URGENT").contains(output.path("riskLevel").asText());
            }
        }
        if(intents.isEmpty())intents.add(task("OTHER",question,run+":i1",0.0));
        for(JsonNode t:intents)resolve((ObjectNode)t,context,question);
        ObjectNode result=object().put("schemaVersion",2).put("originalQuestion",question).put("needHuman",human).put("riskLevel",human?"HIGH":"LOW").put("sentiment","NEUTRAL");recognition.put("totalElapsedMs",(System.nanoTime()-recognitionStarted)/1000000).put("chatInputTokens",in.get()-initialInput).put("chatOutputTokens",out.get()-initialOutput);
        result.set("intents",intents);result.set("recognition",recognition);return result;
    }
    static void validateModel(JsonNode n,String question){
        if(n.has("needHuman")&&!n.path("needHuman").isBoolean())throw error(422,"AGENT_OUTPUT_INVALID","升级标记必须为布尔值");
        if(n.has("riskLevel")&&!Set.of("LOW","NORMAL","HIGH","URGENT").contains(n.path("riskLevel").asText()))throw error(422,"AGENT_OUTPUT_INVALID","风险级别不正确");
        if(!n.path("intents").isArray()||n.path("intents").isEmpty()||n.path("intents").size()>2)throw error(422,"AGENT_OUTPUT_INVALID","意图结构不正确");
        for(var i:n.path("intents")){double confidence=i.path("confidence").asDouble(-1);if(!i.path("confidence").isNumber()||!IntentCatalog.ROUTES.containsKey(i.path("code").asText())||!Double.isFinite(confidence)||confidence<0||confidence>1)throw error(422,"AGENT_OUTPUT_INVALID","意图编码或置信度不正确");
            String f=i.path("questionFragment").asText();if(f.isBlank()||!question.contains(f))throw error(422,"AGENT_OUTPUT_INVALID","问题片段不在原句中");
            if(i.has("rewrittenQuestion")) {
                String rewrite=i.path("rewrittenQuestion").asText();
                if(!i.path("rewrittenQuestion").isTextual()||rewrite.isBlank()||rewrite.length()>2000)throw error(422,"AGENT_OUTPUT_INVALID","重写问题不正确");
                for(Pattern pattern:List.of(ORDER,DISPUTE))if(pattern.matcher(rewrite).results().anyMatch(m->!f.contains(m.group())))throw error(422,"AGENT_OUTPUT_INVALID","重写问题包含无依据业务编号");
            }
            if(!i.path("slots").isObject())throw error(422,"AGENT_OUTPUT_INVALID","槽位结构不正确");
            for(String key:List.of("orderNo","disputeNo","itemId")){JsonNode value=i.path("slots").path(key);if(!value.isMissingNode()&&!value.isNull()&&(!value.isTextual()||value.asText().isBlank()||!f.contains(value.asText())))throw error(422,"AGENT_OUTPUT_INVALID","业务编号没有原句依据");if("itemId".equals(key)&&!value.isMissingNode()&&!value.isNull()&&ITEM_ID.matcher(f).results().noneMatch(m->m.group(1).equals(value.asText())))throw error(422,"AGENT_OUTPUT_INVALID","商品编号格式不正确");}}
    }
    static ObjectNode task(String code,String fragment,String id,Double confidence){ObjectNode t=object().put("intentInstanceId",id).put("code",code).put("priority",1).put("questionFragment",fragment).put("rewrittenQuestion",fragment).put("resolutionState","READY");
        if(confidence==null)t.putNull("confidence");else t.put("confidence",confidence);t.putNull("similarity");t.putNull("clarifyQuestion");t.set("slots",object());t.set("slotSources",object());t.set("missingSlots",array());t.set("requiredSlots",node(IntentCatalog.slots(code)));return t;}
    static void resolve(ObjectNode task,JsonNode context,String question){
        ObjectNode slots=(ObjectNode)task.get("slots"),sources=(ObjectNode)task.get("slotSources");String fragment=task.path("questionFragment").asText();
        for(var entry:Map.of("orderNo",ORDER,"disputeNo",DISPUTE).entrySet()){var matches=entry.getValue().matcher(fragment).results().map(m->m.group().toUpperCase(Locale.ROOT)).distinct().toList();if(matches.size()==1)slots.put(entry.getKey(),matches.getFirst());else if(matches.size()>1)slots.remove(entry.getKey());}
        var itemIds=ITEM_ID.matcher(fragment).results().map(m->m.group(1)).distinct().toList();if(itemIds.size()==1)slots.put("itemId",itemIds.getFirst());else if(itemIds.size()>1)slots.remove("itemId");
        slots.fieldNames().forEachRemaining(k->sources.put(k,"CURRENT_MESSAGE"));
        ArrayNode missing=array();for(var required:task.path("requiredSlots")){String key=required.asText();if(!slots.hasNonNull(key)){
            JsonNode candidates=context.path("confirmedEntities").path(key);
            if(candidates.isArray()&&candidates.size()==1&&question.matches("(?s).*(这个订单|那个订单|这笔订单|这个售后|这个商品|这个产品|那个呢|这笔呢|那一笔|刚才那笔|还没到账|还没到).*")){JsonNode entity=candidates.get(0);
                if(entity.isTextual()||entity.path("confirmed").asBoolean()) {
                    slots.set(key,entity.isTextual()?entity:entity.path("value"));sources.put(key,"CONFIRMED_CONTEXT");
                }else missing.add(key);}
            else missing.add(key);}}
        task.set("missingSlots",missing);
        double confidence=task.path("confidence").isNull()?1:task.path("confidence").asDouble();
        if(!missing.isEmpty() || (confidence>=.45&&confidence<.75))task.put("resolutionState","NEED_CLARIFICATION").put("clarifyQuestion",missing.isEmpty()?"请再说明一下你想查询的具体问题。":"请提供这项问题对应的"+(missing.get(0).asText().equals("orderNo")?"订单号":missing.get(0).asText().equals("disputeNo")?"售后单号":"商品编号")+"。");
        if(confidence<.45){task.put("code","OTHER");task.put("resolutionState","READY");task.set("missingSlots",array());}
    }
    static ObjectNode resume(String question,JsonNode context){
        String target=context.path("clarifyingIntentInstanceId").asText();if(target.isBlank())return null;
        for(var value:context.path("pendingIntents")){if(!target.equals(value.path("intentInstanceId").asText()))continue;ObjectNode task=value.deepCopy();String key=task.path("missingSlots").path(0).asText();
            Pattern p=key.equals("orderNo")?ORDER:key.equals("disputeNo")?DISPUTE:key.equals("itemId")?ITEM_ID:Pattern.compile("(?!)");var matches=p.matcher(question).results().map(m->key.equals("itemId")?m.group(1):m.group().toUpperCase(Locale.ROOT)).toList();
            boolean bareItemId="itemId".equals(key)&&question.strip().matches("\\d{3,18}");if(bareItemId&&matches.isEmpty())matches=List.of(question.strip());
            String remainder=bareItemId?"":p.matcher(question).replaceAll("").strip();
            // Only an unambiguous slot-only answer can resume an instance.
            if(matches.size()!=1 || !remainder.matches("(?:是|商品|商品编号|商品ID|订单号|订单|售后单号|售后单|编号|这个|这个订单|选|选择|第一个|第二个|[:：，,。!！?？\\s])*")) {
                if(question.matches("(?s).*(查询|结算|退款|投诉|另外|规则|手续费|提现|售后|订单).*") && !question.matches("(?s).*(那个|这个|不知道|不清楚).*")) return null;
                ObjectNode retry=object().put("schemaVersion",2).put("originalQuestion",question).put("needHuman",false);
                task.put("resolutionState","NEED_CLARIFICATION");
                retry.set("intents",array().add(task));retry.set("recognition",object().put("source","CONTEXT_RESUME"));return retry;
            }
            ((ObjectNode)task.get("slots")).put(key,matches.getFirst());((ObjectNode)task.get("slotSources")).put(key,"USER_SELECTION");ArrayNode missing=array();for(var r:task.path("requiredSlots"))if(!task.path("slots").hasNonNull(r.asText()))missing.add(r.asText());task.set("missingSlots",missing);
            task.put("resolutionState",missing.isEmpty()?"READY":"NEED_CLARIFICATION");if(missing.isEmpty())task.putNull("clarifyQuestion");
            ObjectNode result=object().put("schemaVersion",2).put("originalQuestion",question).put("needHuman",false);result.set("intents",array().add(task));result.set("recognition",object().put("source","CONTEXT_RESUME"));return result;
        }return null;
    }
    static ObjectNode mock(String question,JsonNode context) {
        String code=context.path("lastIntent").asText();
        if(IntentCatalog.ROUTES.containsKey(code)&&!IntentCatalog.slots(code).isEmpty()
            && question.matches("(?:那个呢|这笔呢|那一笔呢|还没到账|还没到|这个商品(?:价格|库存)?呢?|这个产品(?:价格|库存)?呢?)[?？。!！]*")) {
            ObjectNode task=object().put("code",code).put("confidence",.95).put("questionFragment",question);
            task.set("slots",object());ObjectNode result=object().put("needHuman",false);result.set("intents",array().add(task));return result;
        }
        return mock(question);
    }
    static ObjectNode mock(String question){ArrayNode tasks=array();for(String part:question.split("[，,；;]|再查|另外")){String q=part.strip();if(q.isEmpty()||l1(q,20)!=null)continue;String code;
        if(human(q))code="HUMAN_HELP";else if(q.contains("退款")||q.contains("售后")||q.contains("退货"))code=q.matches(".*(进度|到哪|状态).*")?"AFTER_SALE_PROGRESS":"AFTER_SALE_GUIDE";
        else if(q.contains("提现"))code="WITHDRAW_RULE";else if(q.contains("手续费"))code="SETTLEMENT_RULE";else if(q.contains("结算"))code=q.contains("订单")||ORDER.matcher(q).find()?"SETTLEMENT":"SETTLEMENT_RULE";
        else if(ITEM_ID.matcher(q).find()||q.matches(".*(商品|产品).*(购买|价格|库存|介绍|怎么样).*"))code="PRE_SALE_PRODUCT";
        else if(q.matches(".*(时间记录|时间线).*"))code="ORDER_TIMELINE";else if(q.contains("订单列表")||q.contains("最近订单"))code="ORDER_SEARCH";
        else if(q.matches(".*(订单|交付|发货|到账).*")||ORDER.matcher(q).find())code="ORDER_STATUS";
        else if(q.matches(".*(规则|担保|禁售).*"))code="PLATFORM_RULE";else if(q.contains("实名")||q.contains("绑定"))code="ACCOUNT_RULE";else code="OTHER";
        ObjectNode t=object().put("code",code).put("confidence",code.equals("OTHER")?.2:.95).put("questionFragment",q);t.set("slots",object());tasks.add(t);if(tasks.size()==2)break;}
        ArrayNode filtered=array();boolean hasDomainIntent=false;
        for(JsonNode t:tasks){if(!"OTHER".equals(t.path("code").asText())){hasDomainIntent=true;filtered.add(t);}}
        if(hasDomainIntent)tasks=filtered;
        if(tasks.isEmpty()){ObjectNode t=object().put("code",Optional.ofNullable(l1(question,20)).orElse("OTHER")).put("confidence",.95).put("questionFragment",question);t.set("slots",object());tasks.add(t);}
        ObjectNode result=object().put("needHuman",human(question));result.set("intents",tasks);return result;}
}
