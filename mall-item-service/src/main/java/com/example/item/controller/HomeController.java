package com.example.item.controller;

import com.example.item.entity.Item;
import com.example.item.service.ProductCatalogService;
import com.example.item.vo.HomeOverviewVO;
import com.example.item.vo.SeckillSessionVO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class HomeController {

    @Autowired
    private ProductCatalogService catalogService;

    /**
     * 首页全景看板聚合接口 (Banner轮播 + 实时战报 + 场次倒计时 + 爆款列表)
     */
    @GetMapping("/home/overview")
    public Map<String, Object> getHomeOverview() {
        HomeOverviewVO overview = catalogService.getHomeOverview();
        Map<String, Object> resp = new HashMap<>();
        resp.put("code", 200);
        resp.put("msg", "success");
        resp.put("data", overview);
        return resp;
    }

    /**
     * 获取秒杀场次及商品列表 (包含倒计时及实时剩余库存)
     */
    @GetMapping("/seckill/sessions")
    public Map<String, Object> getSeckillSessions(
            @RequestParam(value = "sessionId", required = false) Long sessionId) {
        List<SeckillSessionVO> sessions = catalogService.getSeckillSessions(sessionId);
        Map<String, Object> resp = new HashMap<>();
        resp.put("code", 200);
        resp.put("msg", "success");
        resp.put("serverTime", System.currentTimeMillis());
        resp.put("data", sessions);
        return resp;
    }

    /**
     * 多级缓存查询商品详情 (Caffeine -> Redis -> MySQL)
     */
    @GetMapping("/item/{id}")
    public Map<String, Object> getItemDetail(@PathVariable("id") Long id) {
        Item item = catalogService.getItemDetail(id);
        Map<String, Object> resp = new HashMap<>();
        if (item != null) {
            resp.put("code", 200);
            resp.put("msg", "success");
            resp.put("data", item);
        } else {
            resp.put("code", 404);
            resp.put("msg", "商品不存在或已下架");
        }
        return resp;
    }

    /**
     * 查询所有在售商品列表
     */
    @GetMapping("/item/list")
    public Map<String, Object> getItemList() {
        List<Item> list = catalogService.getAllItems();
        Map<String, Object> resp = new HashMap<>();
        resp.put("code", 200);
        resp.put("msg", "success");
        resp.put("data", list);
        return resp;
    }

    /**
     * 服务器基准授时接口 (解决客户端本地时间篡改/不准问题)
     */
    @GetMapping("/system/time")
    public Map<String, Object> getServerTime() {
        Map<String, Object> resp = new HashMap<>();
        resp.put("code", 200);
        resp.put("serverTime", System.currentTimeMillis());
        return resp;
    }

    /**
     * 手动触发指定秒杀场次预热 (将库存载入 Redis)
     */
    @PostMapping("/seckill/preheat")
    public Map<String, Object> preheatStock(@RequestParam("sessionId") Long sessionId) {
        catalogService.preheatSessionStock(sessionId);
        Map<String, Object> resp = new HashMap<>();
        resp.put("code", 200);
        resp.put("msg", "场次 " + sessionId + " 秒杀库存预热成功！");
        return resp;
    }
}
