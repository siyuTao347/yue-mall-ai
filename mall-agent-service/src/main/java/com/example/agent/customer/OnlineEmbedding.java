package com.example.agent.customer;

import com.example.agent.config.RagProperties;
import com.example.agent.rag.embed.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import java.util.*;
import com.fasterxml.jackson.databind.JsonNode;
import static com.example.agent.customer.CustomerJson.*;

@Component
public class OnlineEmbedding {
    private final RagProperties props;
    private final RestClient client;
    public OnlineEmbedding(RagProperties props) {
        this.props=props;
        CustomerHttpRequestFactory factory=new CustomerHttpRequestFactory(9000);
        client=RestClient.builder().baseUrl(props.getEmbedding().getBaseUrl().isBlank()?"https://dashscope.aliyuncs.com/compatible-mode":props.getEmbedding().getBaseUrl())
            .defaultHeader("Authorization","Bearer "+props.getEmbedding().getApiKey()).requestFactory(factory).build();
    }
    public String model(){return props.getEmbedding().getModel();}
    public boolean mock(){return "MOCK".equalsIgnoreCase(props.getEmbedding().getProvider());}
    public record Result(float[] vector,Integer tokens) {}
    public float[] embed(String text,String model,int dim) { return embedWithUsage(text,model,dim).vector(); }
    public Result embedWithUsage(String text,String model,int dim) {
        float[] values;Integer tokens=null;
        if(mock()) { values=new MockEmbeddingClient(model,dim).embed(List.of(text)).getFirst();tokens=0; }
        else {
            JsonNode result=read(client.post().uri("/v1/embeddings").body(Map.of("model",model,"input",List.of(text),"dimensions",dim)).retrieve().body(String.class));
            if(result.path("usage").path("total_tokens").canConvertToInt())tokens=result.path("usage").path("total_tokens").asInt();
            JsonNode data=result.path("data");
            if(data.size()!=1 || data.get(0).path("index").asInt(-1)!=0)throw error(503,"RAG_EMBEDDING_INVALID","向量响应不完整");
            JsonNode v=data.get(0).path("embedding");values=new float[v.size()];for(int i=0;i<v.size();i++)values[i]=(float)v.get(i).asDouble(Double.NaN);
        }
        validate(values,dim);return new Result(values,tokens);
    }
    static void validate(float[] vector,int dim){double norm=0;if(vector.length!=dim)throw new IllegalArgumentException("Vector dimension mismatch");for(float f:vector){if(!Float.isFinite(f))throw new IllegalArgumentException("Non-finite vector");norm+=(double)f*f;}if(norm<=0)throw new IllegalArgumentException("Zero vector");}
}
