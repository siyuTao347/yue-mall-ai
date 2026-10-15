package com.example.agent.customer;

import com.fasterxml.jackson.databind.JsonNode;
import com.alibaba.cloud.ai.dashscope.api.DashScopeApi;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatModel;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatOptions;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import java.util.Map;
import java.util.HashSet;
import java.util.concurrent.atomic.AtomicInteger;
import java.time.Duration;
import java.util.Set;
import java.util.function.Consumer;
import static com.example.agent.customer.CustomerJson.*;

@Component
public class CustomerModels {
    private final CustomerProperties props;
    private final CustomerPromptRegistry prompts;
    private final ChatClient master,specialist;
    @org.springframework.beans.factory.annotation.Autowired
    public CustomerModels(CustomerProperties props,CustomerPromptRegistry prompts){this.props=props;this.prompts=prompts;
        if("MOCK".equals(props.getProvider())){master=null;specialist=null;return;}
        if(!"DASHSCOPE".equals(props.getProvider())||props.getApiKey().isBlank())throw new IllegalStateException("DASHSCOPE requires CUSTOMER_SERVICE_API_KEY");
        master=client(props.getMasterModel());specialist=client(props.getResponseModel());
    }
    CustomerModels(CustomerProperties props,ChatClient master,ChatClient specialist) {
        this(props,master,specialist,new CustomerPromptRegistry());
    }
    CustomerModels(CustomerProperties props,ChatClient master,ChatClient specialist,CustomerPromptRegistry prompts) {
        this.props=props;this.master=master;this.specialist=specialist;this.prompts=prompts;
    }
    private ChatClient client(String model){CustomerHttpRequestFactory factory=new CustomerHttpRequestFactory(props.getModelTimeoutMs());
        var api=DashScopeApi.builder().baseUrl(dashScopeApiRoot(props.getBaseUrl())).apiKey(props.getApiKey()).restClientBuilder(RestClient.builder().requestFactory(factory)).build();
        var chat=DashScopeChatModel.builder().dashScopeApi(api).defaultOptions(modelOptions(model)).retryTemplate(RetryTemplate.builder().maxAttempts(1).fixedBackoff(1).build()).build();return ChatClient.create(chat);}
    private static DashScopeChatOptions modelOptions(String model) {
        // qwen3.8-flash requires the multimodal endpoint even for text-only prompts.
        return DashScopeChatOptions.builder().withModel(model).multiModel(true)
            .enableThinking(false).withTemperature(0.1).withMaxToken(1200).build();
    }
    private static String dashScopeApiRoot(String baseUrl){
        String normalized=baseUrl==null?"":baseUrl.strip().replaceAll("/+$","");
        // Official DashScope workspace endpoints end in /api/v1, while this SDK
        // already adds /api/v1/... to each request path.
        return normalized.endsWith("/api/v1")?normalized.substring(0,normalized.length()-7):normalized;
    }
    public boolean mock(){return master==null;}
    public String classify(String question,String context,CustomerBudget budget,AtomicInteger in,AtomicInteger out){
        JsonNode parsed=read(context);JsonNode memory=parsed.path("pending");
        String system=prompts.render("master-planner-v3",Map.of(
            "maxIntents",props.getMaxIntents(),
            "rollingSummary",CustomerContextWindowBuilder.rollingSummary(memory),
            "activeEntitiesJson",write(CustomerContextWindowBuilder.activeEntities(memory)),
            "pendingIntentsJson",write(memory.path("pendingIntents")),
            "recentMessagesJson",write(CustomerContextWindowBuilder.recentHistory(parsed.path("history"))),
            "userQuestion",question));
        return call(master,system,"请根据模板中的输入和 JSON 合约完成规划。",null,Map.of(),budget,in,out);
    }
    public String synthesizeResponse(String question,JsonNode subQuestions,JsonNode facts,JsonNode sources,CustomerBudget budget,AtomicInteger in,AtomicInteger out,Consumer<String> deltaSink) {
        Set<String> allowed=new HashSet<>();if(sources!=null&&sources.isArray())for(JsonNode source:sources)allowed.add(source.path("sourceNo").asText());
        CustomerStreamingAnswerGuard guard=new CustomerStreamingAnswerGuard(deltaSink,allowed);
        try {
            boolean afterSale=false;for(JsonNode task:subQuestions)if(task.path("code").asText().startsWith("AFTER_SALE")){afterSale=true;break;}
            String userPrompt="请根据证据和规则直接回答用户问题。";
            String system=afterSale?prompts.render("after-sale-react-v3",Map.of("question",CustomerContextWindowBuilder.clip(question,1200),
                    "facts",write(CustomerContextWindowBuilder.facts(facts)),"knowledge",knowledgeSourcesXml(sources),"subQuestionsJson",write(CustomerContextWindowBuilder.facts(subQuestions))))
                :prompts.render("response-synthesizer-v3",Map.of(
                    "toolFactsJson",write(CustomerContextWindowBuilder.facts(facts)),"knowledgeSourcesXml",knowledgeSourcesXml(sources),
                    "subQuestionsJson",write(CustomerContextWindowBuilder.facts(subQuestions)),"userQuestion",CustomerContextWindowBuilder.clip(question,1200)));
            String answer=budget.call(props.getModelTimeoutMs(),()->{
                specialist.prompt().system(system).user(userPrompt).stream().content()
                    .doOnNext(guard::accept).collectList().block(Duration.ofMillis(props.getModelTimeoutMs()));
                return guard.finish();
            });
            in.addAndGet(com.example.agent.rag.split.TextUtils.estimateTokens(system+"\n"+userPrompt));
            if(answer!=null)out.addAndGet(com.example.agent.rag.split.TextUtils.estimateTokens(answer));
            return answer==null||answer.isBlank()?guard.fail():answer;
        } catch(RuntimeException failure) {
            if(guard.isBlocked()||guard.hasEmitted())return guard.fail();
            throw failure;
        }
    }
    private static String knowledgeSourcesXml(JsonNode sources) {
        StringBuilder xml=new StringBuilder();
        int remaining=2500;if(sources!=null&&sources.isArray())for(JsonNode source:sources) {
            if(remaining<=0)break;
            String content=CustomerContextWindowBuilder.clip(source.path("content").asText(),Math.min(800,remaining));
            xml.append("<source id=\"").append(CustomerPromptRegistry.xmlText(source.path("sourceNo").asText())).append("\">")
                .append("<title>").append(CustomerPromptRegistry.xmlText(CustomerContextWindowBuilder.clip(source.path("title").asText(),160))).append("</title>")
                .append("<title_path>").append(CustomerPromptRegistry.xmlText(CustomerContextWindowBuilder.clip(source.path("titlePath").asText(),200))).append("</title_path>")
                .append("<content>").append(CustomerPromptRegistry.xmlText(content)).append("</content></source>");
            remaining-=content.length();
        }
        return xml.toString();
    }
    private String call(ChatClient client,String system,String user,Object tools,Map<String,Object> context,CustomerBudget budget,AtomicInteger in,AtomicInteger out){
        return budget.call(props.getModelTimeoutMs(),()->{var spec=client.prompt().system(system).user(user);if(tools!=null)spec=spec.tools(tools).toolContext(context);
            ChatResponse response=spec.call().chatResponse();if(response==null)throw error(503,"AGENT_MODEL_EMPTY","模型未返回结果");
            in.addAndGet(response.getMetadata().getUsage().getPromptTokens());out.addAndGet(response.getMetadata().getUsage().getCompletionTokens());return response.getResult().getOutput().getText();});
    }
}
