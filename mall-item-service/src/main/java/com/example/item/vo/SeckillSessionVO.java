package com.example.item.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SeckillSessionVO {
    private Long sessionId;
    private String sessionName;
    private Long startTime;
    private Long endTime;
    /**
     * 0-即将开抢(预热中), 1-疯抢中(进行中), 2-已结束
     */
    private Integer status;
    private String statusText;
    /**
     * 倒计时毫秒数 (若status=0则是距开始，若status=1则是距结束，若status=2则是0)
     */
    private Long countDownMs;
    private List<SeckillItemVO> items;
}
