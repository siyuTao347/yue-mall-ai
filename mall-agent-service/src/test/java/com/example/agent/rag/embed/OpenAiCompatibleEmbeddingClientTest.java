package com.example.agent.rag.embed;

import com.example.agent.config.RagProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class OpenAiCompatibleEmbeddingClientTest {

    private final RagProperties.Embedding props = new RagProperties.Embedding();
    private final OpenAiCompatibleEmbeddingClient client;

    {
        props.setApiKey("test-key");
        client = new OpenAiCompatibleEmbeddingClient(props, new ObjectMapper());
    }

    @Test
    @DisplayName("OpenAI 兼容模式缺少 API Key 时启动失败")
    void rejectsMissingApiKey() {
        RagProperties.Embedding missingKey = new RagProperties.Embedding();

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> new OpenAiCompatibleEmbeddingClient(missingKey, new ObjectMapper()));

        assertEquals("RAG_EMBEDDING_API_KEY is required when rag.embedding.provider=OPENAI_COMPATIBLE",
                exception.getMessage());
    }

    @Test
    @DisplayName("返回向量维度与配置一致并按 index 归位")
    void parsesVectorsByIndex() {
        String response = """
                {"data":[
                  {"index":1,"embedding":[0.3,0.1]},
                  {"index":0,"embedding":[0.1,0.2]}
                ]}
                """;
        props.setDim(2);

        var vectors = client.parse(response, 2);

        assertEquals(2, vectors.size());
        assertEquals(0.4472136d, vectors.get(0)[0], 1e-6);
        assertEquals(0.9486833d, vectors.get(1)[0], 1e-6);
    }

    @Test
    @DisplayName("返回维度不一致时拒绝入库")
    void rejectsDimensionMismatch() {
        props.setDim(3);

        EmbeddingException exception = assertThrows(EmbeddingException.class,
                () -> client.parse("{\"data\":[{\"index\":0,\"embedding\":[0.1,0.2]}]}", 1));

        assertEquals("Embedding 维度不匹配: expected=3, actual=2", exception.getMessage());
    }

    @Test
    void splitsTwentySixExamplesIntoTwentyAndSixAndPreservesOrderAndUsage() throws Exception {
        props.setDim(2);
        var builder=RestClient.builder().baseUrl("https://embedding.example.test");
        var server=MockRestServiceServer.bindTo(builder).build();
        var batched=new OpenAiCompatibleEmbeddingClient(props,new ObjectMapper(),builder.build());
        var texts=IntStream.range(0,26).mapToObj(i->"sample-"+i).toList();
        expectBatch(server,texts.subList(0,20),0,20);
        expectBatch(server,texts.subList(20,26),20,6);
        var result=batched.embedWithUsage(texts);
        server.verify();
        assertEquals(26,result.vectors().size());
        assertEquals(26,result.tokens());
        for(int i=0;i<26;i++)
            assertEquals((i+1)/Math.sqrt((i+1)*(i+1)+1d),result.vectors().get(i)[0],1e-6);
    }

    @Test
    void respectsSmallerConfiguredBatchesAndDoesNotInventMissingUsage() throws Exception {
        props.setDim(2);props.setBatchSize(2);
        var builder=RestClient.builder().baseUrl("https://embedding.example.test");
        var server=MockRestServiceServer.bindTo(builder).build();
        var batched=new OpenAiCompatibleEmbeddingClient(props,new ObjectMapper(),builder.build());
        expectBatch(server,List.of("a","b"),0,2);
        expectBatch(server,List.of("c"),2,null);
        assertNull(batched.embedWithUsage(List.of("a","b","c")).tokens());
        server.verify();
    }

    @Test
    void failedLaterBatchDoesNotReturnPartialVectors() throws Exception {
        props.setDim(2);props.setBatchSize(2);
        var builder=RestClient.builder().baseUrl("https://embedding.example.test");
        var server=MockRestServiceServer.bindTo(builder).build();
        var batched=new OpenAiCompatibleEmbeddingClient(props,new ObjectMapper(),builder.build());
        expectBatch(server,List.of("a","b"),0,2);
        server.expect(requestTo("https://embedding.example.test/v1/embeddings"))
            .andRespond(withStatus(HttpStatus.BAD_REQUEST).body("{\"error\":{\"message\":\"invalid input\"}}"));
        assertThrows(EmbeddingException.class,()->batched.embedWithUsage(List.of("a","b","c")));
        server.verify();
    }

    @Test
    void rejectsNonPositiveBatchSize() {
        props.setBatchSize(0);
        assertThrows(IllegalArgumentException.class,()->new OpenAiCompatibleEmbeddingClient(props,new ObjectMapper()));
    }

    private void expectBatch(MockRestServiceServer server,List<String> inputs,int offset,Integer tokens) throws Exception {
        var mapper=new ObjectMapper();
        var response=mapper.createObjectNode();
        var data=response.putArray("data");
        // Reverse response order to ensure provider-local indexes are respected in every batch.
        for(int i=inputs.size()-1;i>=0;i--)
            data.addObject().put("index",i).putArray("embedding").add(offset+i+1).add(1);
        if(tokens!=null)response.putObject("usage").put("total_tokens",tokens);
        server.expect(requestTo("https://embedding.example.test/v1/embeddings"))
            .andExpect(content().json(mapper.writeValueAsString(java.util.Map.of("model",props.getModel(),"input",inputs,"dimensions",2))))
            .andRespond(withSuccess(mapper.writeValueAsString(response),MediaType.APPLICATION_JSON));
    }
}
