package com.example.item.service;

import api.risk.RiskDecisionResult;
import api.risk.RiskEvaluateRequest;
import api.risk.SensitiveWordScanner;
import com.example.item.entity.CardSecret;
import com.example.item.entity.Item;
import com.example.item.mapper.CardSecretMapper;
import com.example.item.mapper.ItemAuditMapper;
import com.example.item.mapper.ItemMapper;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AssetListingServiceTest {
    private ItemMapper itemMapper;
    private CardSecretMapper cardSecretMapper;
    private CryptoService cryptoService;
    private RiskClient riskClient;
    private AssetListingService service;

    @BeforeEach
    void setUp() {
        itemMapper = mock(ItemMapper.class);
        cardSecretMapper = mock(CardSecretMapper.class);
        cryptoService = new CryptoService("unit-test-card-key");
        riskClient = mock(RiskClient.class);
        when(riskClient.sensitiveWords()).thenReturn(SensitiveWordScanner.defaultWords());
        when(riskClient.evaluate(any(RiskEvaluateRequest.class))).thenReturn(pass());
        service = new AssetListingService(itemMapper, mock(ItemAuditMapper.class),
                cardSecretMapper, cryptoService, riskClient);
    }

    @Test
    void importCardsDeduplicatesInputAndInsertsOneBatch() {
        when(itemMapper.selectById(1L)).thenReturn(draftItem());
        when(cardSecretMapper.selectExistingHashes(eq(1L), any())).thenReturn(List.of());
        when(cardSecretMapper.batchInsert(any())).thenReturn(2);
        when(itemMapper.increaseDraftStock(1L, 2)).thenReturn(1);

        int count = service.importCards(1L, 10L, List.of(" ABC-001 ", "ABC-001", "DEF-002"));

        Assertions.assertEquals(2, count);
        ArgumentCaptor<List<CardSecret>> captor = ArgumentCaptor.forClass(List.class);
        verify(cardSecretMapper).batchInsert(captor.capture());
        Assertions.assertEquals(2, captor.getValue().size());
        Assertions.assertEquals("ABC-001", cryptoService.decrypt(new String(captor.getValue().get(0).getSecretCipher())));
        Assertions.assertEquals("DEF-002", cryptoService.decrypt(new String(captor.getValue().get(1).getSecretCipher())));
        verify(cardSecretMapper, never()).insert(any(CardSecret.class));
        verify(itemMapper).increaseDraftStock(1L, 2);
    }

    @Test
    void importCardsDoesNotIncreaseStockWhenHashConflicts() {
        when(itemMapper.selectById(1L)).thenReturn(draftItem());
        when(cardSecretMapper.selectExistingHashes(eq(1L), any())).thenReturn(List.of("hash"));

        Assertions.assertThrows(IllegalArgumentException.class,
                () -> service.importCards(1L, 10L, List.of("ABC-001", "ABC-002")));
        verify(cardSecretMapper, never()).batchInsert(any());
        verify(itemMapper, never()).increaseDraftStock(any(), any());
    }

    @Test
    void importCardsSplitsLargeInputIntoBatches() {
        when(itemMapper.selectById(1L)).thenReturn(draftItem());
        when(cardSecretMapper.selectExistingHashes(eq(1L), any())).thenReturn(List.of());
        when(cardSecretMapper.batchInsert(any())).thenReturn(200).thenReturn(1);
        when(itemMapper.increaseDraftStock(eq(1L), eq(201))).thenReturn(1);

        List<String> secrets = new java.util.ArrayList<>();
        for (int index = 0; index < 201; index++) {
            secrets.add("CARD-" + index);
        }
        int count = service.importCards(1L, 10L, secrets);

        Assertions.assertEquals(201, count);
        verify(cardSecretMapper, times(2)).batchInsert(any());
    }

    @Test
    void auditOnlyProcessesPendingItemsWithStock() {
        Item item = draftItem();
        item.setAuditStatus("PENDING");
        item.setStock(0);
        when(itemMapper.selectById(1L)).thenReturn(item);

        Assertions.assertThrows(IllegalArgumentException.class,
                () -> service.audit(1L, 1L, "APPROVE", "库存不足"));
        verify(itemMapper, never()).updateById(any(Item.class));

        item.setStock(1);
        when(itemMapper.approveAudit(1L)).thenReturn(1);
        service.audit(1L, 1L, "APPROVE", "通过");
        verify(itemMapper).approveAudit(1L);
    }

    @Test
    void auditRejectsAlreadyReviewedItem() {
        Item item = draftItem();
        item.setAuditStatus("APPROVED");
        item.setStock(1);
        when(itemMapper.selectById(1L)).thenReturn(item);

        Assertions.assertThrows(IllegalArgumentException.class,
                () -> service.audit(1L, 1L, "REJECT", "重复审核"));
        verify(itemMapper, never()).approveAudit(any());
        verify(itemMapper, never()).rejectAudit(any(), any());
    }

    @Test
    void submitOnlyChangesDraftOrRejectedItemByCondition() {
        Item item = draftItem();
        item.setAuditStatus("REJECTED");
        when(itemMapper.selectById(1L)).thenReturn(item);
        when(itemMapper.submitAudit(1L, 10L)).thenReturn(1);

        Item result = service.submit(1L, 10L);

        Assertions.assertEquals("PENDING", result.getAuditStatus());
        verify(itemMapper).submitAudit(1L, 10L);
        verify(itemMapper, never()).updateById(any(Item.class));
    }

    @Test
    void submitRejectsIllegalAssetBeforeCallingRiskService() {
        Item item = draftItem();
        item.setItemName("虚拟货币账号");
        when(itemMapper.selectById(1L)).thenReturn(item);

        Assertions.assertThrows(IllegalArgumentException.class, () -> service.submit(1L, 10L));

        verify(riskClient, never()).evaluate(any(RiskEvaluateRequest.class));
        verify(itemMapper, never()).submitAudit(any(), any());
    }

    @Test
    void submitContinuesWhenRiskServiceDegradesToPass() {
        Item item = draftItem();
        item.setItemName("Valor AK47");
        when(itemMapper.selectById(1L)).thenReturn(item);
        when(riskClient.evaluate(any(RiskEvaluateRequest.class)))
                .thenReturn(RiskDecisionResult.degraded("RE-LISTING-TEST"));
        when(itemMapper.submitAudit(1L, 10L)).thenReturn(1);

        Item result = service.submit(1L, 10L);

        Assertions.assertEquals("PENDING", result.getAuditStatus());
        verify(itemMapper).submitAudit(1L, 10L);
    }

    @Test
    void createSupportsManualDeliveryStock() {
        Item item = service.create(20L, 10L, Map.of(
                "itemName", "Valor AK47",
                "price", "199.00",
                "assetType", "VIRTUAL_SKIN",
                "deliveryMode", "MANUAL_DELIVERY",
                "stock", "5"
        ));

        Assertions.assertEquals("VIRTUAL_SKIN", item.getAssetType());
        Assertions.assertEquals("MANUAL_DELIVERY", item.getDeliveryMode());
        Assertions.assertEquals(5, item.getStock());
        verify(itemMapper).insert(item);
    }

    @Test
    void createRejectsManualDeliveryWithoutStock() {
        Assertions.assertThrows(IllegalArgumentException.class, () -> service.create(20L, 10L, Map.of(
                "itemName", "Valor AK47",
                "price", "199.00",
                "deliveryMode", "MANUAL_DELIVERY"
        )));
        verify(itemMapper, never()).insert(any(Item.class));
    }

    @Test
    void createRejectsAutoCardForNonCardAsset() {
        Assertions.assertThrows(IllegalArgumentException.class, () -> service.create(20L, 10L, Map.of(
                "itemName", "Valor AK47",
                "price", "199.00",
                "assetType", "VIRTUAL_SKIN",
                "deliveryMode", "AUTO_CARD"
        )));
        verify(itemMapper, never()).insert(any(Item.class));
    }

    private Item draftItem() {
        Item item = new Item();
        item.setId(1L);
        item.setMerchantId(10L);
        item.setAssetType("CARD");
        item.setDeliveryMode("AUTO_CARD");
        item.setAuditStatus("DRAFT");
        return item;
    }

    private RiskDecisionResult pass() {
        return RiskDecisionResult.builder()
                .action(RiskDecisionResult.ACTION_PASS)
                .riskScore(0)
                .riskLevel("LOW")
                .degraded(false)
                .message("pass")
                .hitRules(List.of())
                .build();
    }
}
