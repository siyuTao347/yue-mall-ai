package com.example.item.controller;

import api.context.UserContext;
import com.example.item.provider.SeckillServiceImpl;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

@RestController
@RequestMapping("/api/seckill")
@CrossOrigin(origins = "*")
public class SeckillController {

    @Autowired
    private SeckillServiceImpl seckillService;

    /**
     * 获取动态秒杀路径 Token (安全防刷机制，活动未开始或无权限不可获取)
     */
    @RequestMapping(value = "/getPath", method = {RequestMethod.GET, RequestMethod.POST})
    public Map<String, Object> getPath(
            @RequestParam("itemId") Long itemId,
            @RequestParam(value = "userId", required = false) Long userId) {
        Long effectiveUserId = UserContext.getUserId();
        if (effectiveUserId == null) {
            effectiveUserId = (userId != null) ? userId : 1L;
        }
        String token = seckillService.createPathToken(effectiveUserId, itemId);
        Map<String, Object> resp = new HashMap<>();
        resp.put("code", 200);
        resp.put("msg", "success");
        resp.put("pathToken", token);
        return resp;
    }

    /**
     * 带安全令牌的企业级秒杀下单接口
     */
    @PostMapping("/{pathToken}/doSeckill")
    public Map<String, Object> doSeckillWithToken(
            @PathVariable("pathToken") String pathToken,
            @RequestParam("itemId") Long itemId,
            @RequestParam(value = "userId", required = false) Long userId) {
        Long effectiveUserId = UserContext.getUserId();
        if (effectiveUserId == null) {
            effectiveUserId = (userId != null) ? userId : 1L;
        }
        long startTime = System.currentTimeMillis();
        Map<String, Object> result = seckillService.doSeckillWithResult(effectiveUserId, itemId, pathToken);
        long costTime = System.currentTimeMillis() - startTime;
        result.put("costTime", costTime + "ms");
        return result;
    }

    /**
     * 兼容原秒杀压测接口
     * 请求示例: http://localhost:8082/api/seckill/doSeckill?itemId=1
     */
    @GetMapping("/doSeckill")
    public String doSeckill(@RequestParam("itemId") Long itemId,
                            @RequestParam(value = "userId", required = false) Long userId) {
        Long effectiveUserId = UserContext.getUserId();
        if (effectiveUserId == null) {
            effectiveUserId = (userId != null) ? userId : ThreadLocalRandom.current().nextLong(100000, 999999);
        }

        long startTime = System.currentTimeMillis();
        String result = seckillService.doSeckill(effectiveUserId, itemId);
        long costTime = System.currentTimeMillis() - startTime;

        return "耗时:" + costTime + "ms | 结果: " + result;
    }
}
