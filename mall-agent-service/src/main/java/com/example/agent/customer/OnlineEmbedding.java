package com.example.agent.customer;

import com.example.agent.rag.embed.EmbeddingClient;
import com.example.agent.rag.embed.MockEmbeddingClient;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.*;

/** CustomerAgent adapter over the shared RAG embedding client with bounded local and Redis caches. */
@Component
public class OnlineEmbedding {
    private record CacheEntry(float[] vector) {}
    private static final int MAX_LOCAL_ENTRIES=5000;
    private static final long TTL_MILLIS=Duration.ofHours(24).toMillis();
    private final EmbeddingClient client;
    private final StringRedisTemplate redis;
    private final Cache<String,CacheEntry> local=Caffeine.newBuilder().maximumSize(MAX_LOCAL_ENTRIES).expireAfterWrite(Duration.ofHours(24)).build();

    @Autowired public OnlineEmbedding(EmbeddingClient client,StringRedisTemplate redis){this.client=client;this.redis=redis;}
    public OnlineEmbedding(EmbeddingClient client){this(client,null);}

    public String model() { return client.model(); }
    public int dim() { return client.dim(); }
    public boolean mock() { return client instanceof MockEmbeddingClient; }

    public record Result(float[] vector,Integer tokens,boolean cached) {
        public Result(float[] vector,Integer tokens){this(vector,tokens,false);}
    }
    public record BatchResult(List<float[]> vectors,Integer tokens,int cacheHits) {
        public BatchResult(List<float[]> vectors,Integer tokens){this(vectors,tokens,0);}
    }

    public float[] embed(String text,String model,int dim) {return embedWithUsage(text,model,dim).vector();}
    public Result embedWithUsage(String text,String model,int dim) {
        BatchResult result=embedBatchWithUsage(List.of(text),model,dim);
        return new Result(result.vectors().getFirst(),result.tokens(),result.cacheHits()>0);
    }

    public BatchResult embedBatchWithUsage(List<String> texts,String model,int dim) {
        if(texts==null||texts.isEmpty())return new BatchResult(List.of(),0,0);
        if(!client.model().equals(model)||client.dim()!=dim)
            throw CustomerJson.error(503,"RAG_EMBEDDING_CONFIG_MISMATCH","已发布向量版本与当前 Embedding 配置不一致");
        List<float[]> vectors=new ArrayList<>(Collections.nCopies(texts.size(),null));
        Map<String,List<Integer>> misses=new LinkedHashMap<>();int hits=0;
        for(int i=0;i<texts.size();i++) {
            String key=cacheKey(texts.get(i),model,dim);float[] value=localGet(key);
            if(value==null)value=redisGet(key,dim);
            if(value!=null){vectors.set(i,value);localPut(key,value);hits++;}
            else misses.computeIfAbsent(key,ignored->new ArrayList<>()).add(i);
        }
        if(misses.isEmpty())return new BatchResult(copyVectors(vectors),0,hits);
        List<String> missedTexts=new ArrayList<>(misses.size());for(List<Integer> indices:misses.values())missedTexts.add(texts.get(indices.getFirst()));
        if(!client.model().equals(model)||client.dim()!=dim)
            throw CustomerJson.error(503,"RAG_EMBEDDING_CONFIG_MISMATCH","已发布向量版本与当前 Embedding 配置不一致");
        EmbeddingClient.BatchResult embedded=client.embedWithUsage(missedTexts);
        if(embedded.vectors().size()!=misses.size())throw CustomerJson.error(503,"RAG_EMBEDDING_INVALID","向量响应条数不匹配");
        int index=0;for(var entry:misses.entrySet()) {
            float[] vector=embedded.vectors().get(index++);validate(vector,dim);
            for(int original:entry.getValue())vectors.set(original,vector.clone());
            localPut(entry.getKey(),vector);redisPut(entry.getKey(),vector);
        }
        return new BatchResult(copyVectors(vectors),embedded.tokens(),hits);
    }

    private String cacheKey(String text,String model,int dim){return "agent:emb:"+model+":"+dim+":"+CustomerJson.hash(CustomerJson.normalize(text));}
    private float[] localGet(String key){CacheEntry entry=local.getIfPresent(key);return entry==null?null:entry.vector().clone();}
    private void localPut(String key,float[] vector){local.put(key,new CacheEntry(vector.clone()));}
    private float[] redisGet(String key,int dim){
        if(redis==null)return null;
        try{String encoded=redis.opsForValue().get(key);if(encoded==null)return null;byte[] bytes=Base64.getDecoder().decode(encoded);if(bytes.length!=dim*Float.BYTES)return null;ByteBuffer buffer=ByteBuffer.wrap(bytes);float[] vector=new float[dim];for(int i=0;i<dim;i++)vector[i]=buffer.getFloat();validate(vector,dim);return vector;}
        catch(RuntimeException unavailable){return null;}
    }
    private void redisPut(String key,float[] vector){
        if(redis==null)return;
        try{ByteBuffer buffer=ByteBuffer.allocate(vector.length*Float.BYTES);for(float value:vector)buffer.putFloat(value);redis.opsForValue().set(key,Base64.getEncoder().encodeToString(buffer.array()),Duration.ofMillis(TTL_MILLIS));}
        catch(RuntimeException ignored){}
    }
    private static List<float[]> copyVectors(List<float[]> input){List<float[]> copy=new ArrayList<>(input.size());for(float[] vector:input)copy.add(vector.clone());return List.copyOf(copy);}

    static void validate(float[] vector,int dim){double norm=0;if(vector.length!=dim)throw new IllegalArgumentException("Vector dimension mismatch");for(float f:vector){if(!Float.isFinite(f))throw new IllegalArgumentException("Non-finite vector");norm+=(double)f*f;}if(norm<=0)throw new IllegalArgumentException("Zero vector");}
}
