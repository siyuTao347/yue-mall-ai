package com.example.user.annoation;

import java.lang.annotation.*;

@Target(ElementType.METHOD) // 作用在方法上
@Retention(RetentionPolicy.RUNTIME) // 运行时生效
@Documented
public @interface SendCodeLimit {
    // 限制的时间窗口，默认 60 秒
    long time() default 60;

    // 提示信息
    String message() default "操作过于频繁，请1分钟后再试";
}
