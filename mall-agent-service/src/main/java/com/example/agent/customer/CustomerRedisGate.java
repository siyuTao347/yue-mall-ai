package com.example.agent.customer;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.util.List;

/** Redis-backed per-user lease and sliding-window gate, with caller-managed DB fallback. */
@Component
public class CustomerRedisGate {
    private static final DefaultRedisScript<String> ACQUIRE=new DefaultRedisScript<>("""
        local old = redis.call('GET', KEYS[3])
        if old then
          local sep = string.find(old, '|', 1, true)
          if not sep or string.sub(old, 1, sep - 1) ~= ARGV[1] then return 'IDEMPOTENCY_CONFLICT' end
          return 'REPLAY'
        end
        if redis.call('EXISTS', KEYS[1]) == 1 then return 'BUSY' end
        local time = redis.call('TIME')
        local now = tonumber(time[1]) * 1000 + math.floor(tonumber(time[2]) / 1000)
        redis.call('ZREMRANGEBYSCORE', KEYS[2], '-inf', now - 60000)
        if redis.call('ZCARD', KEYS[2]) >= tonumber(ARGV[4]) then return 'RATE_LIMITED' end
        if redis.call('SET', KEYS[1], ARGV[2], 'NX', 'EX', ARGV[5]) == false then return 'BUSY' end
        redis.call('ZADD', KEYS[2], now, ARGV[2])
        redis.call('EXPIRE', KEYS[2], 120)
        return 'ACQUIRED'
        """,String.class);
    private static final DefaultRedisScript<Long> COMPLETE=new DefaultRedisScript<>("""
        if redis.call('GET', KEYS[1]) == ARGV[1] then return redis.call('DEL', KEYS[1]) end
        return 0
        """,Long.class);
    private static final DefaultRedisScript<Long> RECORD_IDEMPOTENCY=new DefaultRedisScript<>("""
        if redis.call('GET', KEYS[1]) == ARGV[2] then
          redis.call('SET', KEYS[2], ARGV[1] .. '|' .. ARGV[2], 'EX', 86400)
          return 1
        end
        return 0
        """,Long.class);
    private static final DefaultRedisScript<Long> ABORT=new DefaultRedisScript<>("""
        if redis.call('GET', KEYS[1]) == ARGV[1] then redis.call('DEL', KEYS[1]) end
        local idem = redis.call('GET', KEYS[3])
        if idem and string.sub(idem, string.len(ARGV[2]) + 2) == ARGV[1] then redis.call('DEL', KEYS[3]) end
        redis.call('ZREM', KEYS[2], ARGV[1])
        return 1
        """,Long.class);

    private final StringRedisTemplate redis;
    public CustomerRedisGate(StringRedisTemplate redis){this.redis=redis;}

    public String acquire(long userId,String session,String clientId,String requestHash,String runNo,int leaseSeconds) {
        List<String> keys=keys(userId,session,clientId);
        try {
            String result=redis.execute(ACQUIRE,keys,requestHash,runNo,"5",Integer.toString(leaseSeconds));
            return result==null?"UNAVAILABLE":result;
        } catch(RuntimeException unavailable) { return "UNAVAILABLE"; }
    }
    public void complete(long userId,String runNo) {
        try {redis.execute(COMPLETE,List.of(gateKey(userId)),runNo);}catch(RuntimeException ignored){}
    }
    public void record(long userId,String session,String clientId,String requestHash,String runNo) {
        try {redis.execute(RECORD_IDEMPOTENCY,List.of(gateKey(userId),keys(userId,session,clientId).get(2)),requestHash,runNo);}catch(RuntimeException ignored){}
    }
    public void abort(long userId,String session,String clientId,String requestHash,String runNo) {
        try {redis.execute(ABORT,keys(userId,session,clientId),runNo,requestHash);}catch(RuntimeException ignored){}
    }
    private static List<String> keys(long userId,String session,String clientId) {
        String prefix="agent:{"+userId+"}:";
        return List.of(prefix+"gate",prefix+"rate",prefix+"idem:"+session+":"+clientId);
    }
    private static String gateKey(long userId){return "agent:{"+userId+"}:gate";}
}
