package com.example.agent.customer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import static com.example.agent.customer.CustomerJson.*;

/** Keeps model context bounded while preserving the most recent conversation and entities. */
final class CustomerContextWindowBuilder {
    private CustomerContextWindowBuilder() {}

    static String rollingSummary(JsonNode context) {
        return clip(context.path("summary").asText(""), 200);
    }

    static JsonNode recentHistory(JsonNode history) {
        ArrayNode result=array();if(!history.isArray())return result;
        List<JsonNode> selected=new ArrayList<>();int budget=2800;
        for(int i=history.size()-1;i>=0&&selected.size()<8&&budget>0;i--) {
            JsonNode message=history.get(i);String content=clip(message.path("content").asText(""),Math.min(700,budget));
            if(content.isBlank())continue;
            selected.add(object().put("role",message.path("role").asText("USER")).put("content",content));budget-=content.length();
        }
        for(int i=selected.size()-1;i>=0;i--)result.add(selected.get(i));
        return result;
    }

    static JsonNode activeEntities(JsonNode context) {
        ObjectNode result=object();if(context.path("activeFocus").isObject())result.set("activeFocus",context.path("activeFocus").deepCopy());
        JsonNode registry=context.path("entityRegistry");
        for(String name:List.of("orders","disputes","items")) {
            JsonNode entries=registry.path(name);if(!entries.isArray()||entries.isEmpty())entries=context.path("confirmedEntities").path(switch(name){case "orders"->"orderNo";case "disputes"->"disputeNo";default->"itemId";});
            if(!entries.isArray())continue;
            List<JsonNode> latest=new ArrayList<>();for(int i=entries.size()-1;i>=0;i--)latest.add(entries.get(i));
            latest.sort(Comparator.comparingInt((JsonNode entry)->entry.path("lastMentionedTurn").asInt(0)).reversed());
            ArrayNode values=array();for(int i=0;i<Math.min(3,latest.size());i++)values.add(latest.get(i).deepCopy());
            result.set(name,values);
        }
        return result;
    }

    static JsonNode facts(JsonNode value) {return prune(value,0);}
    private static JsonNode prune(JsonNode value,int depth) {
        if(value==null||value.isNull())return value;
        if(value.isTextual())return node(clip(value.asText(),depth>2?300:700));
        if(value.isArray()) {
            ArrayNode result=array();for(int i=0;i<Math.min(12,value.size());i++)result.add(prune(value.get(i),depth+1));return result;
        }
        if(value.isObject()) {
            ObjectNode result=object();var fields=value.fields();int count=0;
            while(fields.hasNext()&&count++<40){var field=fields.next();result.set(field.getKey(),prune(field.getValue(),depth+1));}
            return result;
        }
        return value.deepCopy();
    }

    static String clip(String value,int maxChars) {
        String text=redact(value==null?"":value).replaceAll("\\s+"," ").strip();
        return text.length()<=maxChars?text:text.substring(0,maxChars);
    }
}
