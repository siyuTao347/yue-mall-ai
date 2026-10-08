package com.example.item.service;

import api.trade.AssetReservationResult;
import com.example.item.entity.AssetReservation;
import com.example.item.entity.CardSecret;
import com.example.item.entity.Item;
import com.example.item.mapper.AssetReservationMapper;
import com.example.item.mapper.CardSecretMapper;
import com.example.item.mapper.ItemMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AssetServiceTest {
    private ItemMapper itemMapper;
    private CardSecretMapper cardSecretMapper;
    private AssetReservationMapper reservationMapper;
    private AssetService service;

    @BeforeEach
    void setUp() {
        itemMapper = mock(ItemMapper.class);
        cardSecretMapper = mock(CardSecretMapper.class);
        reservationMapper = mock(AssetReservationMapper.class);
        service = new AssetService(itemMapper, cardSecretMapper, reservationMapper,
                mock(CryptoService.class), new ObjectMapper());
    }

    @Test
    void reserveMovesItemStockToFrozenWhenCardsAreAvailable() {
        Item item = approvedItem();
        when(itemMapper.selectById(1L)).thenReturn(item);
        when(cardSecretMapper.lockAvailableCards(1L, 2)).thenReturn(List.of(card(10L), card(11L)));
        when(cardSecretMapper.reserveStock(1L, 2)).thenReturn(1);
        when(cardSecretMapper.lockByIds(any(), eq("TR1"), any(), any())).thenReturn(2);

        AssetReservationResult result = service.reserve(1L, 2, "TR1", 15);

        Assertions.assertTrue(result.isSuccess());
        verify(cardSecretMapper).reserveStock(1L, 2);
        verify(cardSecretMapper).lockByIds(eq(List.of(10L, 11L)), eq("TR1"), any(), any());
        verify(reservationMapper).insert(any(AssetReservation.class));
    }

    @Test
    void reserveSupportsManualDeliveryWithoutCardSecrets() {
        Item item = approvedItem();
        item.setDeliveryMode("MANUAL_DELIVERY");
        when(itemMapper.selectById(1L)).thenReturn(item);
        when(itemMapper.reserveStock(1L, 2)).thenReturn(1);

        AssetReservationResult result = service.reserve(1L, 2, "TR1", 15);

        Assertions.assertTrue(result.isSuccess());
        verify(itemMapper).reserveStock(1L, 2);
        verify(cardSecretMapper, never()).lockAvailableCards(any(), any());
        verify(reservationMapper).insert(any(AssetReservation.class));
    }

    @Test
    void reserveRejectsUnsupportedDeliveryMode() {
        Item item = approvedItem();
        item.setDeliveryMode("UNKNOWN");
        when(itemMapper.selectById(1L)).thenReturn(item);

        AssetReservationResult result = service.reserve(1L, 2, "TR1", 15);

        Assertions.assertFalse(result.isSuccess());
        verify(itemMapper, never()).reserveStock(any(), any());
        verify(cardSecretMapper, never()).lockAvailableCards(any(), any());
        verify(reservationMapper, never()).insert(any(AssetReservation.class));
    }

    @Test
    void confirmClearsFrozenStockAfterCardsAreSold() {
        AssetReservation reservation = reservation("CONFIRM");
        when(reservationMapper.selectOne(any())).thenReturn(reservation);
        when(cardSecretMapper.markSoldByReservation(any(), any())).thenReturn(2);
        when(cardSecretMapper.confirmStock(1L, 2)).thenReturn(1);

        boolean confirmed = service.confirm("TR1");

        Assertions.assertTrue(confirmed);
        verify(cardSecretMapper).markSoldByReservation(any(), any());
        verify(cardSecretMapper).confirmStock(1L, 2);
    }

    @Test
    void releaseRestoresItemStockWhenReservationExpires() {
        AssetReservation reservation = reservation("RELEASE");
        when(reservationMapper.selectOne(any())).thenReturn(reservation);
        when(cardSecretMapper.releaseByOrderAndReservation(eq("TR1"), any(), any())).thenReturn(2);
        when(cardSecretMapper.releaseStock(1L, 2)).thenReturn(1);

        boolean released = service.release("TR1");

        Assertions.assertTrue(released);
        verify(cardSecretMapper).releaseByOrderAndReservation(eq("TR1"), any(), any());
        verify(cardSecretMapper).releaseStock(1L, 2);
    }

    @Test
    void manualDeliveryReservationUsesItemStock() {
        AssetReservation reservation = reservation("MANUAL");
        reservation.setCardSecretIds("[]");
        when(reservationMapper.selectOne(any())).thenReturn(reservation);
        when(itemMapper.releaseStock(1L, 2)).thenReturn(1);

        Assertions.assertTrue(service.release("TR1"));
        verify(itemMapper).releaseStock(1L, 2);
        verify(cardSecretMapper, never()).releaseStock(any(), any());
    }

    @Test
    void manualDeliveryConfirmationOnlyDeductsFrozenStock() {
        AssetReservation reservation = reservation("MANUAL_CONFIRM");
        reservation.setCardSecretIds("[]");
        when(reservationMapper.selectOne(any())).thenReturn(reservation);
        when(itemMapper.confirmStock(1L, 2)).thenReturn(1);

        Assertions.assertTrue(service.confirm("TR1"));
        verify(itemMapper).confirmStock(1L, 2);
        verify(cardSecretMapper, never()).markSoldByReservation(any(), any());
    }

    @Test
    void manualDeliveryRefundRestoresSoldStockOnce() {
        AssetReservation reservation = reservation("MANUAL_REFUND");
        reservation.setStatus("CONFIRMED");
        reservation.setCardSecretIds("[]");
        when(reservationMapper.selectOne(any())).thenReturn(reservation);
        when(reservationMapper.markRefundedByOrderNo(eq("TR1"), any())).thenAnswer(invocation -> {
            reservation.setStatus("REFUNDED");
            return 1;
        });
        when(itemMapper.restoreRefundedStock(1L, 2)).thenReturn(1);

        Assertions.assertTrue(service.invalidateByOrderNo("TR1"));
        Assertions.assertTrue(service.invalidateByOrderNo("TR1"));

        verify(itemMapper).restoreRefundedStock(1L, 2);
        verify(cardSecretMapper, never()).invalidateByOrderNo(any(), any());
    }

    private Item approvedItem() {
        Item item = new Item();
        item.setId(1L);
        item.setMerchantId(30L);
        item.setSellerId(20L);
        item.setItemName("Steam Gift Card");
        item.setPrice(new BigDecimal("100.00"));
        item.setStatus(1);
        item.setAuditStatus("APPROVED");
        item.setDeliveryMode("AUTO_CARD");
        return item;
    }

    private CardSecret card(Long id) {
        CardSecret card = new CardSecret();
        card.setId(id);
        card.setItemId(1L);
        card.setStatus("AVAILABLE");
        return card;
    }

    private AssetReservation reservation(String action) {
        AssetReservation reservation = new AssetReservation();
        reservation.setReservationNo("RSV-" + action);
        reservation.setOrderNo("TR1");
        reservation.setItemId(1L);
        reservation.setMerchantId(30L);
        reservation.setQuantity(2);
        reservation.setCardSecretIds("[10,11]");
        reservation.setStatus("RESERVED");
        reservation.setExpireTime(LocalDateTime.now().plusMinutes(1));
        return reservation;
    }
}
