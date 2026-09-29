package com.example.item.service;

import com.example.item.entity.CardSecret;
import com.example.item.entity.Item;
import com.example.item.mapper.CardSecretMapper;
import com.example.item.mapper.ItemAuditMapper;
import com.example.item.mapper.ItemMapper;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AssetListingServiceTest {
    private ItemMapper itemMapper;
    private CardSecretMapper cardSecretMapper;
    private CryptoService cryptoService;
    private AssetListingService service;

    @BeforeEach
    void setUp() {
        itemMapper = mock(ItemMapper.class);
        cardSecretMapper = mock(CardSecretMapper.class);
        cryptoService = new CryptoService("unit-test-card-key");
        service = new AssetListingService(itemMapper, mock(ItemAuditMapper.class),
                cardSecretMapper, cryptoService);
    }

    @Test
    void importCardsDeduplicatesInputBeforeIncreasingStock() {
        when(itemMapper.selectById(1L)).thenReturn(draftItem());
        when(itemMapper.increaseDraftStock(1L, 2)).thenReturn(1);

        int count = service.importCards(1L, 10L, List.of(" ABC-001 ", "ABC-001", "DEF-002"));

        Assertions.assertEquals(2, count);
        ArgumentCaptor<CardSecret> captor = ArgumentCaptor.forClass(CardSecret.class);
        verify(cardSecretMapper, times(2)).insert(captor.capture());
        Assertions.assertEquals("ABC-001", cryptoService.decrypt(new String(captor.getAllValues().get(0).getSecretCipher())));
        Assertions.assertEquals("DEF-002", cryptoService.decrypt(new String(captor.getAllValues().get(1).getSecretCipher())));
        verify(itemMapper).increaseDraftStock(1L, 2);
    }

    @Test
    void importCardsDoesNotIncreaseStockWhenHashConflicts() {
        when(itemMapper.selectById(1L)).thenReturn(draftItem());
        when(cardSecretMapper.insert(any(CardSecret.class)))
                .thenReturn(1)
                .thenThrow(new DuplicateKeyException("duplicate card"));

        Assertions.assertThrows(DuplicateKeyException.class,
                () -> service.importCards(1L, 10L, List.of("ABC-001", "ABC-002")));
        verify(itemMapper, never()).increaseDraftStock(any(), any());
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
}
