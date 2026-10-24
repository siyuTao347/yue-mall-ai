package com.example.agent.customer;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static com.example.agent.customer.CustomerJson.*;

/** Evaluates a frozen release's L2 -> L3 classification only; no business tools or session writes. */
@Component
public class IntentReleaseEvaluator {
    private final CustomerModels models;
    private final CustomerProperties properties;

    public IntentReleaseEvaluator(CustomerModels models,CustomerProperties properties) {
        this.models=models;
        this.properties=properties;
    }

    ObjectNode evaluate(IntentReleaseService.Evaluation test,ObjectNode l2) {
        boolean accepted="L2_RAG".equals(l2.path("source").asText());
        ObjectNode result=object().put("query",redact(test.query())).put("expected",test.expectedIntent())
            .put("l2Actual",l2.path("code").asText("OTHER")).put("l2Accepted",accepted)
            .put("source",accepted?"L2_RAG":"L3_LLM").put("status","EVALUATED");
        for(String key:List.of("similarity","runnerUpScore","threshold","minMargin","fallbackReason","candidates"))
            if(l2.has(key))result.set(key,l2.get(key).deepCopy());
        if(l2.path("candidates").isArray()&&!l2.path("candidates").isEmpty())
            result.put("topCandidateIntent",l2.path("candidates").get(0).path("intent_code").asText());
        AtomicInteger input=new AtomicInteger(),output=new AtomicInteger();
        ArrayNode actual=array();
        long started=System.nanoTime();
        try {
            if(accepted)actual.add(l2.path("code").asText());
            else {
                String question=redact(test.query());
                ObjectNode context=object();
                ArrayNode history=array().add(object().put("role","USER").put("content",question));
                var plan=CustomerMaster.classifyValidated(models,question,context,history,
                    new CustomerBudget(properties.getRunTimeoutMs()),input,output);
                var intents=CustomerMaster.modelIntents(plan,question,"release-evaluation",context,properties.getMaxIntents());
                for(var intent:intents) {
                    CustomerMaster.resolve((ObjectNode)intent,context,question);
                    actual.add(intent.path("code").asText());
                }
                result.put("mockL3",models.mock());
            }
            result.set("actualIntents",actual);
            if(actual.size()==1)result.put("actual",actual.get(0).asText());
            else result.putNull("actual");
            result.put("passed",actual.size()==1&&test.expectedIntent().equals(actual.get(0).asText()));
        } catch(RuntimeException failure) {
            var diagnostic=IntentReleaseService.buildFailure("EVALUATING_L3",failure);
            result.put("status","ERROR").put("passed",false).putNull("actual");
            result.set("actualIntents",actual);
            result.set("errorCode",diagnostic.get("errorCode"));
            result.set("error",diagnostic.get("error"));
        }
        result.put("l3InputTokens",input.get()).put("l3OutputTokens",output.get())
            .put("l3ElapsedMs",accepted?0:(System.nanoTime()-started)/1_000_000);
        return result;
    }

    ObjectNode summarize(ArrayNode results) {
        int accepted=0,l2Correct=0,l3Correct=0,correct=0,errors=0,business=0,businessAccepted=0;
        boolean mockL3=false;
        for(var result:results) {
            boolean l2=result.path("l2Accepted").asBoolean(),ok=result.path("passed").asBoolean();
            if(l2) {accepted++;if(ok)l2Correct++;}
            else if(ok)l3Correct++;
            if(ok)correct++;
            if("ERROR".equals(result.path("status").asText()))errors++;
            if(!"OTHER".equals(result.path("expected").asText())) {business++;if(l2)businessAccepted++;}
            mockL3|=result.path("mockL3").asBoolean();
        }
        ObjectNode report=object().put("evaluationMode","L2_THEN_L3")
            .put("businessExecution",false).put("includesL1AndEscalation",false)
            .put("classificationModel",properties.getMasterModel()).put("mockL3",mockL3)
            .put("totalCount",results.size()).put("evaluatedCount",results.size()-errors)
            .put("correctCount",correct).put("errorCount",errors)
            .put("l2AcceptedCount",accepted).put("l2CorrectCount",l2Correct)
            .put("l2WrongAcceptedCount",accepted-l2Correct)
            .put("l3FallbackCount",results.size()-accepted).put("l3CorrectCount",l3Correct)
            .put("passed",!results.isEmpty()&&correct==results.size()&&errors==0);
        ratio(report,"l2Coverage",accepted,results.size());
        ratio(report,"businessL2Coverage",businessAccepted,business);
        ratio(report,"l2Precision",l2Correct,accepted);
        // Errors stay in the denominator: accuracy cannot hide failed model calls.
        ratio(report,"finalAccuracy",correct,results.size());
        return report;
    }

    private static void ratio(ObjectNode report,String field,int numerator,int denominator) {
        if(denominator==0)report.putNull(field);
        else report.put(field,(double)numerator/denominator);
    }
}
