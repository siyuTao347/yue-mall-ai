package com.example.agent.customer;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.example.agent.customer.CustomerJson.*;

class IntentReleaseTest {
    private IntentReleaseService.Snapshot valid(){Map<String,Object> h=new HashMap<>();h.put("release_no","R1");h.put("embedding_model","model-a");h.put("embedding_dim",1536);h.put("expected_intent_count",2);h.put("expected_example_count",2);h.put("default_similarity_threshold",.88);h.put("min_margin",.05);
        var items=new ArrayList<Map<String,Object>>();var examples=new ArrayList<Map<String,Object>>();int n=0;
        for(String code:List.of("ORDER_STATUS","SETTLEMENT")){String text="question"+(++n);items.add(new HashMap<>(Map.of("intent_code",code,"intent_version",1,"route_node","ORDER_AGENT","similarity_threshold",.88,"expected_example_count",1)));examples.add(new HashMap<>(Map.of("example_no","E"+n,"example_version",1,"intent_code",code,"intent_version",1,"example_text",text,"normalized_text",text,"example_hash",hash(text),"embedding_input_hash",hash("model-a|"+text))));}
        var s=new IntentReleaseService.Snapshot(h,items,examples);h.put("manifest_hash",IntentReleaseService.manifest(s));return s;}
    @Test void completeManifestAccepted(){assertDoesNotThrow(()->IntentReleaseService.validate(valid()));}
    @Test void absentCompetitorFailsInsteadOfReducingCatalog(){var s=valid();s.examples().removeLast();assertThrows(RuntimeException.class,()->IntentReleaseService.validate(s));}
    @Test void metadataMutationInvalidatesFrozenHash(){var s=valid();s.items().get(0).put("similarity_threshold",.6);assertThrows(RuntimeException.class,()->IntentReleaseService.validate(s));}
    @Test void staleModelHashAndWrongIntentVersionRejected(){var s=valid();s.examples().get(0).put("embedding_input_hash",hash("old-model|question1"));assertThrows(RuntimeException.class,()->IntentReleaseService.validate(s));var second=valid();second.examples().get(0).put("intent_version",2);assertThrows(RuntimeException.class,()->IntentReleaseService.validate(second));}
    @Test void canonicalManifestIndependentOfMapInsertionOrder(){var s=valid();var h=new TreeMap<>(s.header());assertEquals(IntentReleaseService.manifest(s),IntentReleaseService.manifest(new IntentReleaseService.Snapshot(h,s.items(),s.examples())));}
}
