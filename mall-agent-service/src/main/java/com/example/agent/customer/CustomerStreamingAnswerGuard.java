package com.example.agent.customer;

import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/** Holds a short suffix so sensitive values and citations split across model chunks are checked together. */
final class CustomerStreamingAnswerGuard {
    private static final int WINDOW_CODE_POINTS=48;
    private static final Pattern CITATION=Pattern.compile("\\[(S\\d+)]");
    private static final String FALLBACK="抱歉，这段答复未通过安全校验，请联系人工客服协助处理。";
    private static final String CITATION_FALLBACK="相关规则请参考 [S1]，如需进一步确认可联系人工客服。";
    private final Consumer<String> sink;
    private final Set<String> allowedCitations;
    private final StringBuilder pending=new StringBuilder(),emitted=new StringBuilder();
    private boolean blocked,fallbackSent,citationSeen;

    CustomerStreamingAnswerGuard(Consumer<String> sink,Set<String> allowedCitations){this.sink=sink;this.allowedCitations=allowedCitations;}
    synchronized void accept(String value){
        if(blocked||value==null||value.isEmpty())return;pending.append(value);
        while(pending.codePointCount(0,pending.length())>WINDOW_CODE_POINTS){
            int count=pending.codePointCount(0,pending.length())-WINDOW_CODE_POINTS;int end=pending.offsetByCodePoints(0,count);
            if(!emit(pending.substring(0,end)))return;pending.delete(0,end);
        }
    }
    synchronized String finish(){
        if(!blocked&&!pending.isEmpty()){String tail=pending.toString();pending.setLength(0);emit(tail);}
        if(!blocked&&!allowedCitations.isEmpty()&&!citationSeen){String first=new java.util.TreeSet<>(allowedCitations).first();String extra=CITATION_FALLBACK.replace("[S1]","["+first+"]");emit(extra);}
        if(blocked)sendFallback();return emitted.toString();
    }
    synchronized String fail(){blocked=true;pending.setLength(0);sendFallback();return emitted.toString();}
    synchronized boolean isBlocked(){return blocked;}
    synchronized boolean hasEmitted(){return !emitted.isEmpty();}

    private boolean emit(String value){
        String safe=CustomerJson.redact(value);
        if(!CustomerOutputGuard.safeText(safe)||!validCitations(safe)){blocked=true;pending.setLength(0);sendFallback();return false;}
        if(CITATION.matcher(safe).find())citationSeen=true;
        if(!safe.isEmpty()){emitted.append(safe);try{sink.accept(safe);}catch(RuntimeException ignored){}}
        return true;
    }
    private boolean validCitations(String value){var matcher=CITATION.matcher(value);while(matcher.find())if(!allowedCitations.contains(matcher.group(1)))return false;return true;}
    private void sendFallback(){if(fallbackSent)return;fallbackSent=true;emitted.append(FALLBACK);try{sink.accept(FALLBACK);}catch(RuntimeException ignored){}}
}
