package com.example.item.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.item.entity.Banner;
import com.example.item.entity.Item;
import com.example.item.entity.SeckillItem;
import com.example.item.entity.SeckillSession;
import com.example.item.mapper.BannerMapper;
import com.example.item.mapper.ItemMapper;
import com.example.item.mapper.SeckillItemMapper;
import com.example.item.mapper.SeckillSessionMapper;
import com.example.item.vo.HomeOverviewVO;
import com.example.item.vo.SeckillItemVO;
import com.example.item.vo.SeckillSessionVO;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

@Slf4j
@Service
public class ProductCatalogService {

    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");

    @Autowired
    private ItemMapper itemMapper;

    @Autowired
    private BannerMapper bannerMapper;

    @Autowired
    private SeckillSessionMapper sessionMapper;

    @Autowired
    private SeckillItemMapper seckillItemMapper;

    @Autowired
    private StringRedisTemplate redisTemplate;

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 一级本地缓存 (Caffeine)
     */
    private final Cache<Long, Item> itemLocalCache = Caffeine.newBuilder()
            .maximumSize(500)
            .expireAfterWrite(Duration.ofMinutes(10))
            .build();

    /**
     * 启动时自动预热当前活跃场次的 Redis 库存
     */
    @PostConstruct
    public void initPreheat() {
        try {
            log.info("系统初始化，开始自动预热秒杀库存与场次缓存...");
            List<SeckillSession> sessions = sessionMapper.selectList(null);
            for (SeckillSession session : sessions) {
                preheatSessionStock(session.getId());
            }
            log.info("秒杀库存自动预热完成！");
        } catch (Exception e) {
            log.warn("初始化秒杀库存预热跳过/异常: {}", e.getMessage());
        }
    }

    /**
     * 预热指定场次的秒杀库存到 Redis
     */
    public void preheatSessionStock(Long sessionId) {
        LambdaQueryWrapper<SeckillItem> query = new LambdaQueryWrapper<>();
        query.eq(SeckillItem::getSessionId, sessionId);
        List<SeckillItem> items = seckillItemMapper.selectList(query);
        for (SeckillItem si : items) {
            String stockKey = "seckill:stock:" + si.getItemId();
            // 若 Redis 不存在或者需要同步，设置库存
            Boolean exists = redisTemplate.hasKey(stockKey);
            if (Boolean.FALSE.equals(exists)) {
                redisTemplate.opsForValue().set(stockKey, String.valueOf(si.getRemainStock()));
                log.info("预热商品库存至 Redis: itemId={}, stock={}", si.getItemId(), si.getRemainStock());
            }
        }
    }

    /**
     * 多级缓存获取商品详情 (Caffeine -> Redis -> MySQL)
     */
    public Item getItemDetail(Long itemId) {
        // 1. 查本地一级缓存
        Item item = itemLocalCache.getIfPresent(itemId);
        if (item != null) {
            return item;
        }

        // 2. 查 Redis 二级缓存
        String cacheKey = "item:detail:" + itemId;
        try {
            String json = redisTemplate.opsForValue().get(cacheKey);
            if (json != null && !json.isEmpty()) {
                if ("NULL".equals(json)) {
                    return null; // 防穿透空值
                }
                item = objectMapper.readValue(json, Item.class);
                itemLocalCache.put(itemId, item);
                return item;
            }
        } catch (Exception e) {
            log.warn("读取 Redis 商品缓存异常: {}", e.getMessage());
        }

        // 3. 查数据库 (带 DCL 双重检查防击穿)
        synchronized (this) {
            item = itemLocalCache.getIfPresent(itemId);
            if (item != null) {
                return item;
            }

            item = itemMapper.selectById(itemId);
            if (item != null) {
                try {
                    // 加随机 TTL (30分钟 + 0~5分钟随机偏差) 防雪崩
                    int randomMinutes = 30 + ThreadLocalRandom.current().nextInt(5);
                    redisTemplate.opsForValue().set(cacheKey, objectMapper.writeValueAsString(item), Duration.ofMinutes(randomMinutes));
                } catch (Exception ignored) {}
                itemLocalCache.put(itemId, item);
            } else {
                // 缓存空对象 60 秒防穿透
                redisTemplate.opsForValue().set(cacheKey, "NULL", Duration.ofSeconds(60));
            }
        }
        return item;
    }

    /**
     * 获取所有在架商品列表
     */
    public List<Item> getAllItems() {
        LambdaQueryWrapper<Item> query = new LambdaQueryWrapper<>();
        query.eq(Item::getStatus, 1).eq(Item::getAuditStatus, "APPROVED").orderByDesc(Item::getId);
        return itemMapper.selectList(query);
    }

    /**
     * 获取启用的轮播 Banner
     */
    public List<Banner> getActiveBanners() {
        LambdaQueryWrapper<Banner> query = new LambdaQueryWrapper<>();
        query.eq(Banner::getIsActive, 1).orderByAsc(Banner::getSortOrder);
        return bannerMapper.selectList(query);
    }

    /**
     * 获取秒杀场次导航及商品列表 (精准时钟驱动)
     */
    public List<SeckillSessionVO> getSeckillSessions(Long targetSessionId) {
        long now = System.currentTimeMillis();
        LambdaQueryWrapper<SeckillSession> sessionQuery = new LambdaQueryWrapper<SeckillSession>()
                .orderByAsc(SeckillSession::getStartTime);
        if (targetSessionId != null) {
            sessionQuery.eq(SeckillSession::getId, targetSessionId);
        }

        List<SeckillSession> dbSessions = sessionMapper.selectList(
                sessionQuery
        );

        List<SeckillSessionVO> result = new ArrayList<>();
        for (SeckillSession s : dbSessions) {
            long startMs = s.getStartTime().atZone(BUSINESS_ZONE).toInstant().toEpochMilli();
            long endMs = s.getEndTime().atZone(BUSINESS_ZONE).toInstant().toEpochMilli();

            int dynamicStatus;
            String statusText;
            long countDownMs;

            if (now < startMs) {
                dynamicStatus = 0; // 即将开抢 (预热中)
                statusText = "即将开抢";
                countDownMs = startMs - now;
            } else if (now <= endMs) {
                dynamicStatus = 1; // 进行中
                statusText = "抢购中";
                countDownMs = endMs - now;
            } else {
                dynamicStatus = 2; // 已结束
                statusText = "已结束";
                countDownMs = 0;
            }

            // 查询该场次下的秒杀商品
            List<SeckillItemVO> itemVOList = new ArrayList<>();
            LambdaQueryWrapper<SeckillItem> itemQuery = new LambdaQueryWrapper<>();
            itemQuery.eq(SeckillItem::getSessionId, s.getId()).orderByAsc(SeckillItem::getSortOrder);
            List<SeckillItem> seckillItems = seckillItemMapper.selectList(itemQuery);

            for (SeckillItem si : seckillItems) {
                Item baseItem = getItemDetail(si.getItemId());
                if (baseItem == null) continue;

                // 优先从 Redis 实时获取库存
                String stockKey = "seckill:stock:" + si.getItemId();
                String redisStockStr = redisTemplate.opsForValue().get(stockKey);
                int currentRemain = redisStockStr != null ? Integer.parseInt(redisStockStr) : si.getRemainStock();

                int total = si.getSeckillStock() > 0 ? si.getSeckillStock() : 1;
                int sold = total - currentRemain;
                int percent = Math.min(100, Math.max(0, (sold * 100) / total));

                SeckillItemVO vo = SeckillItemVO.builder()
                        .id(si.getId())
                        .sessionId(s.getId())
                        .itemId(si.getItemId())
                        .itemName(baseItem.getItemName())
                        .subTitle(baseItem.getSubTitle())
                        .imageUrl(baseItem.getImageUrl())
                        .detailHtml(baseItem.getDetailHtml())
                        .originalPrice(baseItem.getPrice())
                        .seckillPrice(si.getSeckillPrice())
                        .seckillStock(si.getSeckillStock())
                        .remainStock(Math.max(0, currentRemain))
                        .percent(percent)
                        .isSoldOut(currentRemain <= 0)
                        .limitPerUser(si.getLimitPerUser())
                        .build();
                itemVOList.add(vo);
            }

            SeckillSessionVO sessionVO = SeckillSessionVO.builder()
                    .sessionId(s.getId())
                    .sessionName(s.getSessionName())
                    .startTime(startMs)
                    .endTime(endMs)
                    .status(dynamicStatus)
                    .statusText(statusText)
                    .countDownMs(countDownMs)
                    .items(itemVOList)
                    .build();

            result.add(sessionVO);
        }

        return result;
    }

    /**
     * 首页全景看板聚合接口
     */
    public HomeOverviewVO getHomeOverview() {
        long serverTime = System.currentTimeMillis();
        List<Banner> banners = getActiveBanners();

        // 首页快讯
        List<String> tickers = Arrays.asList(
                "20:00 场次即将开启，请提前进入会场",
                "玩家 @S1mple 以 ￥99.00 购得 M4A4 咆哮（崭新出厂）",
                "会员消费 1 元得 1 积分，支付成功后自动入账",
                "玩家 @KennyS 购得 AWP 巨龙传说（纪念品级）",
                "14:00 场次内蝴蝶刀 | 渐变大理石剩余 4 件"
        );

        List<SeckillSessionVO> sessions = getSeckillSessions(null);
        // 找出首个进行中的场次，若无则取首个预热中的场次
        SeckillSessionVO activeSession = sessions.stream()
                .filter(s -> s.getStatus() == 1)
                .findFirst()
                .orElse(sessions.stream().filter(s -> s.getStatus() == 0).findFirst().orElse(!sessions.isEmpty() ? sessions.get(0) : null));

        List<Item> hotItems = getAllItems();

        return HomeOverviewVO.builder()
                .serverTime(serverTime)
                .banners(banners)
                .tickers(tickers)
                .sessions(sessions)
                .currentSession(activeSession)
                .hotItems(hotItems)
                .build();
    }
}
