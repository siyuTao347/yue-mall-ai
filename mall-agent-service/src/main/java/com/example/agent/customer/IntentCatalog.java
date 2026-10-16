package com.example.agent.customer;

import java.util.*;

public final class IntentCatalog {
    private IntentCatalog(){}
    public static final Map<String,String> ROUTES=Map.ofEntries(
        Map.entry("GREETING","TEMPLATE_RESPONSE"),Map.entry("BOT_IDENTITY","TEMPLATE_RESPONSE"),Map.entry("OTHER","FALLBACK"),
        Map.entry("ORDER_SEARCH","ORDER_AGENT"),Map.entry("ORDER_STATUS","ORDER_AGENT"),Map.entry("ORDER_TIMELINE","ORDER_AGENT"),Map.entry("SETTLEMENT","ORDER_AGENT"),
        Map.entry("AFTER_SALE_PROGRESS","AFTER_SALE_AGENT"),Map.entry("AFTER_SALE_GUIDE","AFTER_SALE_AGENT"),Map.entry("AFTER_SALE_STATUS_COMBINED","AFTER_SALE_AGENT"),
        Map.entry("PLATFORM_RULE","RULE_AGENT"),Map.entry("SETTLEMENT_RULE","RULE_AGENT"),Map.entry("WITHDRAW_RULE","RULE_AGENT"),Map.entry("ACCOUNT_RULE","RULE_AGENT"),
        Map.entry("COMPLAINT","COMPLAINT_AGENT"),Map.entry("HUMAN_HELP","COMPLAINT_AGENT"),
        Map.entry("PRE_SALE_PRODUCT","PRE_SALE_AGENT"),Map.entry("PRE_SALE_PROCESS","PRE_SALE_AGENT"),Map.entry("PRE_SALE_LIMIT","PRE_SALE_AGENT"),
        Map.entry("MEMORY_QUERY","MEMORY_AGENT"),Map.entry("MEMORY_FORGET","MEMORY_AGENT"));
    public static List<String> slots(String code){return switch(code){
        case "ORDER_STATUS","ORDER_TIMELINE","SETTLEMENT" -> List.of("orderNo");
        case "AFTER_SALE_PROGRESS" -> List.of("disputeNo");
        case "AFTER_SALE_STATUS_COMBINED" -> List.of("orderNo","disputeNo");
        default -> List.of();};}
    public static List<String> optionalSlots(String code){return switch(code){
        case "PRE_SALE_PRODUCT","PRE_SALE_LIMIT" -> List.of("itemId");
        case "COMPLAINT","HUMAN_HELP" -> List.of("orderNo","disputeNo");
        default -> List.of();};}
    public static boolean l2(String code){return ROUTES.containsKey(code)&&!Set.of("GREETING","BOT_IDENTITY","OTHER","MEMORY_QUERY","MEMORY_FORGET").contains(code);}
}
