package com.example.agent.customer;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.example.agent.exception.RagApiException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.Normalizer;
import java.util.*;

public final class CustomerJson {
    private CustomerJson() {}
    public static final ObjectMapper M=new ObjectMapper().findAndRegisterModules();
    public static ObjectNode object(){return M.createObjectNode();}
    public static ArrayNode array(){return M.createArrayNode();}
    public static JsonNode node(Object v){return M.valueToTree(v);}
    public static String write(Object v){try{return M.writeValueAsString(v);}catch(Exception e){throw new IllegalStateException("JSON encoding failed",e);}}
    public static JsonNode read(Object s){try{return s==null?object():M.readTree(s.toString());}catch(Exception e){throw error(422,"AGENT_OUTPUT_INVALID","数据格式不正确");}}
    public static String hash(String text){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
    public static String normalize(String s){return Normalizer.normalize(s,Normalizer.Form.NFKC).toLowerCase(Locale.ROOT).strip().replaceAll("(?U)\\s+"," ");}
    public static String id(String prefix){return prefix+UUID.randomUUID().toString().replace("-","");}
    public static RagApiException error(int status,String code,String message){return new RagApiException(status,code,message);}
    public static String redact(String value) {
        return value.replaceAll("(?i)(bearer\\s+)[a-z0-9._-]+","$1[已隐藏]")
            .replaceAll("(?i)(密码|卡密|支付凭证|password|token)\\s*[:：=]\\s*[^\\s，。]+","$1：[已隐藏]")
            .replaceAll("[\\w.+-]+@[\\w.-]+\\.[a-zA-Z]{2,}","[邮箱已隐藏]")
            .replaceAll("(?<![A-Za-z0-9])1[3-9]\\d{9}(?!\\d)","[手机号已隐藏]")
            .replaceAll("(?<![A-Za-z0-9])\\d{16,19}[Xx]?(?!\\d)","[敏感号码已隐藏]");
    }
}
