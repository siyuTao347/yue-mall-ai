package com.example.item.service;

import api.trade.AssetReservationResult;
import api.trade.CardSecretDTO;
import api.trade.ItemSnapshotDTO;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.item.entity.AssetReservation;
import com.example.item.entity.CardSecret;
import com.example.item.entity.Item;
import com.example.item.mapper.AssetReservationMapper;
import com.example.item.mapper.CardSecretMapper;
import com.example.item.mapper.ItemMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.UUID;
import java.util.List;

@Service
public class AssetService {
    private final ItemMapper itemMapper;
    private final CardSecretMapper cardSecretMapper;
    private final AssetReservationMapper reservationMapper;
    private final CryptoService cryptoService;

    public AssetService(ItemMapper itemMapper, CardSecretMapper cardSecretMapper,
                        AssetReservationMapper reservationMapper, CryptoService cryptoService) {
        this.itemMapper = itemMapper;
        this.cardSecretMapper = cardSecretMapper;
        this.reservationMapper = reservationMapper;
        this.cryptoService = cryptoService;
    }

    @Transactional(rollbackFor = Exception.class)
    public AssetReservationResult reserve(Long itemId, Integer quantity, String orderNo, Integer expireMinutes) {
        if (itemId == null || quantity == null || quantity <= 0 || orderNo == null || orderNo.isBlank()) {
            return AssetReservationResult.fail("参数不合法");
        }
        Item item = itemMapper.selectById(itemId);
        if (item == null || item.getStatus() != 1 || !"APPROVED".equals(item.getAuditStatus())) {
            return AssetReservationResult.fail("商品不存在或未上架");
        }
        String reservationNo = "RSV" + UUID.randomUUID().toString().replace("-", "");
        LocalDateTime now = LocalDateTime.now();
        List<CardSecret> cards = List.of();
        if ("AUTO_CARD".equals(item.getDeliveryMode())) {
            cards = cardSecretMapper.selectList(new LambdaQueryWrapper<CardSecret>()
                    .eq(CardSecret::getItemId, itemId)
                    .eq(CardSecret::getStatus, "AVAILABLE")
                    .last("LIMIT " + quantity + " FOR UPDATE"));
            if (cards.size() < quantity) {
                return AssetReservationResult.fail("卡密库存不足");
            }
            if (cardSecretMapper.reserveStock(itemId, quantity) <= 0) {
                return AssetReservationResult.fail("商品库存不足");
            }
            for (CardSecret card : cards) {
                if (cardSecretMapper.lockById(card.getId(), orderNo, reservationNo, now) <= 0) {
                    throw new IllegalStateException("卡密被并发占用");
                }
            }
        } else if ("MANUAL_DELIVERY".equals(item.getDeliveryMode())) {
            if (itemMapper.reserveStock(itemId, quantity) <= 0) {
                return AssetReservationResult.fail("商品库存不足");
            }
        } else {
            return AssetReservationResult.fail("商品交付方式不支持");
        }

        AssetReservation reservation = new AssetReservation();
        reservation.setReservationNo(reservationNo);
        reservation.setOrderNo(orderNo);
        reservation.setItemId(itemId);
        reservation.setMerchantId(item.getMerchantId());
        reservation.setAssetType(item.getAssetType());
        reservation.setQuantity(quantity);
        reservation.setCardSecretIds(joinIds(cards));
        reservation.setStatus("RESERVED");
        reservation.setExpireTime(now.plusMinutes(expireMinutes == null ? 15 : expireMinutes));
        reservation.setCreatedTime(now);
        reservation.setUpdatedTime(now);
        reservationMapper.insert(reservation);

        return AssetReservationResult.success(reservationNo, snapshot(item), List.of());
    }

    @Transactional(rollbackFor = Exception.class)
    public boolean release(String orderNo) {
        AssetReservation reservation = getByOrderNo(orderNo);
        if (reservation == null || !"RESERVED".equals(reservation.getStatus())) {
            return true;
        }
        boolean cardReservation = hasCardSecrets(reservation);
        if (cardReservation) {
            cardSecretMapper.releaseByReservation(reservation.getReservationNo(), LocalDateTime.now());
        }
        int released = cardReservation
                ? cardSecretMapper.releaseStock(reservation.getItemId(), reservation.getQuantity())
                : itemMapper.releaseStock(reservation.getItemId(), reservation.getQuantity());
        if (released <= 0) {
            throw new IllegalStateException("商品冻结库存释放失败");
        }
        reservation.setStatus("RELEASED");
        reservation.setUpdatedTime(LocalDateTime.now());
        reservationMapper.updateById(reservation);
        return true;
    }

    @Transactional(rollbackFor = Exception.class)
    public boolean confirm(String orderNo) {
        AssetReservation reservation = getByOrderNo(orderNo);
        if (reservation == null) {
            return false;
        }
        if ("CONFIRMED".equals(reservation.getStatus())) {
            return true;
        }
        if (!"RESERVED".equals(reservation.getStatus())) {
            throw new IllegalStateException("资产预留状态不允许确认");
        }
        int confirmed;
        if (hasCardSecrets(reservation)) {
            cardSecretMapper.markSoldByReservation(reservation.getReservationNo(), LocalDateTime.now());
            confirmed = cardSecretMapper.confirmStock(reservation.getItemId(), reservation.getQuantity());
        } else {
            confirmed = itemMapper.confirmStock(reservation.getItemId(), reservation.getQuantity());
        }
        if (confirmed <= 0) {
            throw new IllegalStateException("商品冻结库存确认失败");
        }
        reservation.setStatus("CONFIRMED");
        reservation.setUpdatedTime(LocalDateTime.now());
        reservationMapper.updateById(reservation);
        return true;
    }

    public List<CardSecret> getCardSecrets(String orderNo) {
        return cardSecretMapper.selectList(new LambdaQueryWrapper<CardSecret>()
                .eq(CardSecret::getOrderNo, orderNo)
                .eq(CardSecret::getStatus, "SOLD"));
    }

    public AssetReservation getByOrderNo(String orderNo) {
        return reservationMapper.selectOne(new LambdaQueryWrapper<AssetReservation>()
                .eq(AssetReservation::getOrderNo, orderNo));
    }

    private ItemSnapshotDTO snapshot(Item item) {
        return ItemSnapshotDTO.builder()
                .itemId(item.getId())
                .sellerId(item.getSellerId())
                .merchantId(item.getMerchantId())
                .itemName(item.getItemName())
                .subTitle(item.getSubTitle())
                .imageUrl(item.getImageUrl())
                .price(item.getPrice())
                .assetType(item.getAssetType())
                .deliveryMode(item.getDeliveryMode())
                .riskNotice(item.getRiskNotice())
                .build();
    }

    private String joinIds(List<CardSecret> cards) {
        StringBuilder builder = new StringBuilder("[");
        for (int i = 0; i < cards.size(); i++) {
            if (i > 0) {
                builder.append(',');
            }
            builder.append(cards.get(i).getId());
        }
        return builder.append(']').toString();
    }

    public List<CardSecretDTO> soldCardDTOs(String orderNo) {
        List<CardSecret> cards = getCardSecrets(orderNo);
        List<CardSecretDTO> result = new ArrayList<>(cards.size());
        for (CardSecret card : cards) {
            result.add(CardSecretDTO.builder()
                    .id(card.getId())
                    .secretPlain(cryptoService.decrypt(new String(card.getSecretCipher(), StandardCharsets.UTF_8)))
                    .secretMask(card.getSecretMask())
                    .build());
        }
        return result;
    }

    @Transactional(rollbackFor = Exception.class)
    public boolean invalidateByOrderNo(String orderNo) {
        if (orderNo == null || orderNo.isBlank()) {
            return false;
        }
        AssetReservation reservation = getByOrderNo(orderNo);
        if (reservation == null || "REFUNDED".equals(reservation.getStatus())) {
            return reservation != null;
        }
        if (!"CONFIRMED".equals(reservation.getStatus())) {
            return false;
        }
        LocalDateTime now = LocalDateTime.now();
        if (reservationMapper.markRefundedByOrderNo(orderNo, now) <= 0) {
            return false;
        }
        if (hasCardSecrets(reservation)) {
            cardSecretMapper.invalidateByOrderNo(orderNo, now);
        } else if (itemMapper.restoreRefundedStock(reservation.getItemId(), reservation.getQuantity()) <= 0) {
            throw new IllegalStateException("手动交付商品退款库存恢复失败");
        }
        return true;
    }

    public static List<Long> parseIds(String json) {
        String text = json == null ? "[]" : json;
        return java.util.Arrays.stream(text.replace("[", "").replace("]", "").split(","))
                .filter(s -> !s.isBlank())
                .map(Long::valueOf)
                .toList();
    }

    private boolean hasCardSecrets(AssetReservation reservation) {
        return parseIds(reservation.getCardSecretIds()).size()
                == (reservation.getQuantity() == null ? 0 : reservation.getQuantity());
    }
}
