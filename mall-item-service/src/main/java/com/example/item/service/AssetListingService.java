package com.example.item.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.item.entity.CardSecret;
import com.example.item.entity.Item;
import com.example.item.entity.ItemAudit;
import com.example.item.mapper.CardSecretMapper;
import com.example.item.mapper.ItemAuditMapper;
import com.example.item.mapper.ItemMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class AssetListingService {
    private final ItemMapper itemMapper;
    private final ItemAuditMapper auditMapper;
    private final CardSecretMapper cardSecretMapper;
    private final CryptoService cryptoService;

    public AssetListingService(ItemMapper itemMapper, ItemAuditMapper auditMapper,
                               CardSecretMapper cardSecretMapper, CryptoService cryptoService) {
        this.itemMapper = itemMapper;
        this.auditMapper = auditMapper;
        this.cardSecretMapper = cardSecretMapper;
        this.cryptoService = cryptoService;
    }

    @Transactional(rollbackFor = Exception.class)
    public Item create(Long sellerId, Long merchantId, Map<String, Object> body) {
        BigDecimal price = new BigDecimal(required(body.get("price"), "价格不能为空"));
        if (price.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("价格必须大于 0");
        }
        String assetType = requiredOption(body.get("assetType"), "CARD", List.of("CARD", "VIRTUAL_SKIN", "GAME_ITEM"));
        String deliveryMode = requiredOption(body.get("deliveryMode"), "AUTO_CARD",
                List.of("AUTO_CARD", "MANUAL_DELIVERY"));
        if ("AUTO_CARD".equals(deliveryMode) && !"CARD".equals(assetType)) {
            throw new IllegalArgumentException("自动卡密交付仅支持卡密资产");
        }
        int stock = parseManualStock(body.get("stock"), deliveryMode);
        Item item = new Item();
        item.setMerchantId(merchantId);
        item.setSellerId(sellerId);
        item.setItemName(required(body.get("itemName"), "商品名称不能为空"));
        item.setPrice(price);
        item.setStock(stock);
        item.setFrozenStock(0);
        item.setVersion(0);
        item.setCategoryId(body.get("categoryId") == null ? 1L : Long.valueOf(String.valueOf(body.get("categoryId"))));
        item.setSubTitle(String.valueOf(body.getOrDefault("subTitle", "")));
        item.setImageUrl(String.valueOf(body.getOrDefault("imageUrl", "")));
        item.setDetailHtml(String.valueOf(body.getOrDefault("detailHtml", "")));
        item.setStatus(0);
        item.setAssetType(assetType);
        item.setDeliveryMode(deliveryMode);
        item.setSourceDescription(String.valueOf(body.getOrDefault("sourceDescription", "")));
        item.setRiskNotice(String.valueOf(body.getOrDefault("riskNotice", "虚拟资产请确认后购买")));
        item.setAuditStatus("DRAFT");
        itemMapper.insert(item);
        return item;
    }

    @Transactional(rollbackFor = Exception.class)
    public Item submit(Long itemId, Long merchantId) {
        Item item = getOwnedItem(itemId, merchantId);
        if (itemMapper.submitAudit(itemId, merchantId) <= 0) {
            throw new IllegalArgumentException("当前状态不能提交审核");
        }
        item.setAuditStatus("PENDING");
        insertAudit(itemId, "SUBMIT", null, "提交审核");
        return item;
    }

    @Transactional(rollbackFor = Exception.class)
    public Item audit(Long itemId, Long auditorId, String action, String reason) {
        Item item = itemMapper.selectById(itemId);
        if (item == null) {
            throw new IllegalArgumentException("商品不存在");
        }
        if (!"PENDING".equals(item.getAuditStatus())) {
            throw new IllegalArgumentException("商品不在待审核状态");
        }
        if (item.getStock() == null || item.getStock() <= 0) {
            throw new IllegalArgumentException("商品审核前必须准备库存");
        }
        if ("APPROVE".equals(action)) {
            item.setAuditStatus("APPROVED");
            item.setStatus(1);
        } else if ("REJECT".equals(action)) {
            item.setAuditStatus("REJECTED");
            item.setStatus(0);
            item.setAuditRemark(reason);
        } else {
            throw new IllegalArgumentException("不支持的操作");
        }
        item.setAuditRemark("REJECT".equals(action) ? reason : null);
        int updated = "APPROVE".equals(action)
                ? itemMapper.approveAudit(itemId)
                : itemMapper.rejectAudit(itemId, reason);
        if (updated <= 0) {
            throw new IllegalStateException("商品审核状态已变化，请刷新后重试");
        }
        insertAudit(itemId, action, auditorId, reason);
        return item;
    }

    @Transactional(rollbackFor = Exception.class)
    public int importCards(Long itemId, Long merchantId, List<String> secrets) {
        Item item = getOwnedItem(itemId, merchantId);
        if ("APPROVED".equals(item.getAuditStatus())) {
            throw new IllegalArgumentException("商品已上架，不能直接追加卡密");
        }
        if (!"CARD".equals(item.getAssetType()) || !"AUTO_CARD".equals(item.getDeliveryMode())) {
            throw new IllegalArgumentException("只有自动交付卡密商品才能导入卡密");
        }
        if (secrets == null || secrets.isEmpty()) {
            throw new IllegalArgumentException("卡密不能为空");
        }
        Set<String> plains = new LinkedHashSet<>();
        for (String secret : secrets) {
            if (secret != null && !secret.isBlank()) {
                plains.add(secret.trim());
            }
        }
        if (plains.isEmpty()) {
            throw new IllegalArgumentException("卡密不能为空");
        }
        LocalDateTime now = LocalDateTime.now();
        for (String plain : plains) {
            CardSecret card = new CardSecret();
            card.setItemId(itemId);
            card.setMerchantId(merchantId);
            card.setSecretCipher(cryptoService.encrypt(plain).getBytes());
            card.setSecretHash(cryptoService.sha256(plain));
            card.setSecretMask(cryptoService.mask(plain));
            card.setStatus("AVAILABLE");
            card.setCreatedTime(now);
            card.setUpdatedTime(now);
            cardSecretMapper.insert(card);
        }
        if (itemMapper.increaseDraftStock(itemId, plains.size()) <= 0) {
            throw new IllegalStateException("商品不是草稿状态，卡密库存更新失败");
        }
        return plains.size();
    }

    public List<Item> listBySeller(Long sellerId) {
        return itemMapper.selectList(new LambdaQueryWrapper<Item>()
                .eq(Item::getSellerId, sellerId)
                .orderByDesc(Item::getId));
    }

    public List<Item> listPending() {
        return itemMapper.selectList(new LambdaQueryWrapper<Item>()
                .eq(Item::getAuditStatus, "PENDING")
                .orderByAsc(Item::getId));
    }

    private Item getOwnedItem(Long itemId, Long merchantId) {
        Item item = itemMapper.selectById(itemId);
        if (item == null || item.getMerchantId() == null || !item.getMerchantId().equals(merchantId)) {
            throw new IllegalArgumentException("商品不存在或不属于当前商家");
        }
        return item;
    }

    private void insertAudit(Long itemId, String action, Long auditorId, String reason) {
        ItemAudit audit = new ItemAudit();
        audit.setItemId(itemId);
        audit.setAction(action);
        audit.setAuditorId(auditorId);
        audit.setReason(reason);
        audit.setCreatedTime(LocalDateTime.now());
        auditMapper.insert(audit);
    }

    private String required(Object value, String message) {
        if (value == null || String.valueOf(value).isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return String.valueOf(value).trim();
    }

    private String requiredOption(Object value, String defaultValue, List<String> options) {
        String result = value == null ? defaultValue : String.valueOf(value).trim();
        if (!options.contains(result)) {
            throw new IllegalArgumentException("不支持的资产类型或交付方式");
        }
        return result;
    }

    private int parseManualStock(Object value, String deliveryMode) {
        if ("AUTO_CARD".equals(deliveryMode)) {
            return 0;
        }
        if (value == null || String.valueOf(value).isBlank()) {
            throw new IllegalArgumentException("手动交付商品必须填写库存");
        }
        int stock;
        try {
            stock = Integer.parseInt(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("库存必须是整数");
        }
        if (stock <= 0) {
            throw new IllegalArgumentException("库存必须大于 0");
        }
        return stock;
    }
}
