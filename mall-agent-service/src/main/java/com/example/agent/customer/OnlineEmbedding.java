package com.example.agent.customer;

import com.example.agent.rag.embed.EmbeddingClient;
import com.example.agent.rag.embed.MockEmbeddingClient;
import org.springframework.stereotype.Component;

import java.util.List;

/** CustomerAgent adapter over the shared RAG embedding client. */
@Component
public class OnlineEmbedding {
    private final EmbeddingClient client;

    public OnlineEmbedding(EmbeddingClient client) {
        this.client = client;
    }

    public String model() { return client.model(); }
    public int dim() { return client.dim(); }
    public boolean mock() { return client instanceof MockEmbeddingClient; }

    public record Result(float[] vector,Integer tokens) {}
    public record BatchResult(List<float[]> vectors,Integer tokens) {}

    public float[] embed(String text,String model,int dim) {
        return embedWithUsage(text,model,dim).vector();
    }

    public Result embedWithUsage(String text,String model,int dim) {
        BatchResult result=embedBatchWithUsage(List.of(text),model,dim);
        return new Result(result.vectors().getFirst(),result.tokens());
    }

    public BatchResult embedBatchWithUsage(List<String> texts,String model,int dim) {
        if(texts==null||texts.isEmpty())return new BatchResult(List.of(),0);
        if(!client.model().equals(model)||client.dim()!=dim)
            throw CustomerJson.error(503,"RAG_EMBEDDING_CONFIG_MISMATCH","已发布向量版本与当前 Embedding 配置不一致");
        EmbeddingClient.BatchResult result=client.embedWithUsage(texts);
        if(result.vectors().size()!=texts.size())
            throw CustomerJson.error(503,"RAG_EMBEDDING_INVALID","向量响应条数不匹配");
        for(float[] vector:result.vectors())validate(vector,dim);
        return new BatchResult(result.vectors(),result.tokens());
    }

    static void validate(float[] vector,int dim){double norm=0;if(vector.length!=dim)throw new IllegalArgumentException("Vector dimension mismatch");for(float f:vector){if(!Float.isFinite(f))throw new IllegalArgumentException("Non-finite vector");norm+=(double)f*f;}if(norm<=0)throw new IllegalArgumentException("Zero vector");}
}
