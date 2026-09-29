package com.example.user.annoation;


import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.time.Duration;

@Aspect
@Component
public class RateLimitAspect {

    @Autowired
    private StringRedisTemplate redisTemplate;

    // 环绕通知：拦截所有标注了 @SendCodeLimit 的方法
    @Around("@annotation(com.example.user.annoation.SendCodeLimit)")
    public Object around(ProceedingJoinPoint joinPoint) throws Throwable {
        // 1. 获取方法签名和注解参数
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        Method method = signature.getMethod();
        SendCodeLimit limitAnnotation = method.getAnnotation(SendCodeLimit.class);

        // 2. 约定：被拦截的方法，第一个参数必须是 email 邮箱号
        Object[] args = joinPoint.getArgs();
        if (args.length == 0 || !(args[0] instanceof String)) {
            throw new IllegalArgumentException("接口缺少邮箱参数");
        }
        String email = (String) args[0];

        // 3. 【简历核心亮点：构建 Redis 防刷 Key】
        String redisKey = "rate_limit:email:" + email;

        // 4. 使用 setIfAbsent (底层是 SETNX 命令) 保证原子性
        // 如果这个 Key 不存在，说明1分钟内没发过，设置成功并加上过期时间；
        // 如果 Key 存在，说明1分钟内已经发过了，直接拒绝！
        Boolean isSuccess = redisTemplate.opsForValue().setIfAbsent(
                redisKey,
                "1",
                Duration.ofSeconds(limitAnnotation.time())
        );

        if (Boolean.FALSE.equals(isSuccess)) {
            // 触发限流，直接返回错误提示，不执行具体的发送逻辑
            return limitAnnotation.message();
        }

        // 5. 放行：执行原本的发送验证码业务逻辑
        return joinPoint.proceed();
    }
}
