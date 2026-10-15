package com.example.item.dto;

import com.example.item.entity.DeliveryEvidence;
import com.example.item.entity.DeliveryRecord;
import com.example.item.entity.Dispute;
import com.example.item.entity.TradeOrderStatusLog;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserOrderDetailDTO {
    private UserOrderListDTO order;
    private DeliveryRecord deliveryRecord;
    private List<TradeOrderStatusLog> timeline;
    private Dispute dispute;
    private List<DeliveryEvidence> evidences;
    private String cardSecret;
}
