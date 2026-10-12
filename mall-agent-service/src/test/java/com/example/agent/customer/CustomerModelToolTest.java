package com.example.agent.customer;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.*;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.ai.support.ToolCallbacks;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class CustomerModelToolTest {
    public static class KnowledgeFixture {
        int calls;
        String instance;
        @Tool(name="searchKnowledge", description="Read public knowledge")
        public String search(@ToolParam(description="Question") String query,
                             @ToolParam(required=false,description="Types") List<String> docTypes,ToolContext context) {
            calls++;instance=context.getContext().get("intentInstanceId").toString();return "[]";
        }
    }
    @Test void toolSchemaHidesServerIdentityAndContext() {
        var schema=ToolCallbacks.from(new KnowledgeFixture())[0].getToolDefinition().inputSchema();
        assertTrue(schema.contains("query"));assertTrue(schema.contains("docTypes"));
        for(String hidden:List.of("context","runNo","userId","intentInstanceId","visibility"))assertFalse(schema.contains(hidden),schema);
    }
    @Test void realSpringToolCallingUsesServerContextWithoutAutomaticModelLoop() {
        ChatModel chat=mock(ChatModel.class);
        var message=AssistantMessage.builder().content("").toolCalls(List.of(new AssistantMessage.ToolCall("call1","function","searchKnowledge","{\"query\":\"提现规则\"}"))).build();
        when(chat.call(any(Prompt.class))).thenReturn(new ChatResponse(List.of(new Generation(message))));
        ChatClient client=ChatClient.create(chat);
        CustomerModels models=new CustomerModels(new CustomerProperties(),client,client);
        KnowledgeFixture fixture=new KnowledgeFixture();
        models.retrieve("提现规则",fixture,Map.of("intentInstanceId","r:i1"),new CustomerBudget(5000),new AtomicInteger(),new AtomicInteger());
        assertEquals(1,fixture.calls);assertEquals("r:i1",fixture.instance);verify(chat,times(1)).call(any(Prompt.class));
    }
    @Test void unauthorizedModelToolIsNeverExecuted() {
        ChatModel chat=mock(ChatModel.class);
        var message=AssistantMessage.builder().content("").toolCalls(List.of(new AssistantMessage.ToolCall("call1","function","refundOrder","{}"))).build();
        when(chat.call(any(Prompt.class))).thenReturn(new ChatResponse(List.of(new Generation(message))));
        ChatClient client=ChatClient.create(chat);CustomerModels models=new CustomerModels(new CustomerProperties(),client,client);KnowledgeFixture fixture=new KnowledgeFixture();
        assertThrows(RuntimeException.class,()->models.retrieve("退款",fixture,Map.of(),new CustomerBudget(5000),new AtomicInteger(),new AtomicInteger()));assertEquals(0,fixture.calls);
    }
}
