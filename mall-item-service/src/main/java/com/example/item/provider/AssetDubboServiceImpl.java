package com.example.item.provider;

import api.trade.AssetDubboService;
import api.trade.AssetReservationResult;
import api.trade.CardSecretDTO;
import com.example.item.service.AssetService;
import org.apache.dubbo.config.annotation.DubboService;

import java.util.List;

@DubboService
public class AssetDubboServiceImpl implements AssetDubboService {
    private final AssetService assetService;

    public AssetDubboServiceImpl(AssetService assetService) {
        this.assetService = assetService;
    }

    @Override
    public AssetReservationResult reserve(Long itemId, Integer quantity, String orderNo, Integer expireMinutes) {
        return assetService.reserve(itemId, quantity, orderNo, expireMinutes);
    }

    @Override
    public AssetReservationResult reserve(Long itemId, Integer quantity, String orderNo, Integer expireMinutes,
                                          String idempotencyKey) {
        return assetService.reserve(itemId, quantity, orderNo, expireMinutes, idempotencyKey);
    }

    @Override
    public boolean release(String orderNo) {
        return assetService.release(orderNo);
    }

    @Override
    public boolean release(String orderNo, String idempotencyKey) {
        return assetService.release(orderNo, idempotencyKey);
    }

    @Override
    public boolean confirm(String orderNo) {
        return assetService.confirm(orderNo);
    }

    @Override
    public boolean confirm(String orderNo, String idempotencyKey) {
        return assetService.confirm(orderNo, idempotencyKey);
    }

    @Override
    public List<CardSecretDTO> getSoldCardSecrets(String orderNo) {
        return assetService.soldCardDTOs(orderNo);
    }

    @Override
    public boolean invalidateByOrderNo(String orderNo) {
        return assetService.invalidateByOrderNo(orderNo);
    }

    @Override
    public boolean invalidateByOrderNo(String orderNo, String idempotencyKey) {
        return assetService.invalidateByOrderNo(orderNo, idempotencyKey);
    }
}
