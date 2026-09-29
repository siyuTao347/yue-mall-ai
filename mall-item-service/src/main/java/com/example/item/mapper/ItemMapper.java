package com.example.item.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.item.entity.Item;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface ItemMapper extends BaseMapper<Item> {
    @Update("UPDATE t_item SET audit_status = 'PENDING', status = 0, version = version + 1 " +
            "WHERE id = #{itemId} AND merchant_id = #{merchantId} " +
            "AND audit_status IN ('DRAFT', 'REJECTED')")
    int submitAudit(@Param("itemId") Long itemId, @Param("merchantId") Long merchantId);

    @Update("UPDATE t_item SET audit_status = 'APPROVED', status = 1, audit_remark = NULL, " +
            "version = version + 1 WHERE id = #{itemId} AND audit_status = 'PENDING' AND stock > 0")
    int approveAudit(@Param("itemId") Long itemId);

    @Update("UPDATE t_item SET audit_status = 'REJECTED', status = 0, audit_remark = #{reason}, " +
            "version = version + 1 WHERE id = #{itemId} AND audit_status = 'PENDING' AND stock > 0")
    int rejectAudit(@Param("itemId") Long itemId, @Param("reason") String reason);

    @Update("UPDATE t_item SET stock = stock + #{count}, version = version + 1 " +
            "WHERE id = #{itemId} AND audit_status <> 'APPROVED'")
    int increaseDraftStock(@Param("itemId") Long itemId, @Param("count") Integer count);

    @Update("UPDATE t_item SET stock = stock - #{count}, frozen_stock = frozen_stock + #{count} " +
            "WHERE id = #{itemId} AND stock >= #{count}")
    int tryDeductStock(@Param("itemId") Long itemId, @Param("count") Integer count);

    @Update("UPDATE t_item SET stock = stock - #{count}, frozen_stock = frozen_stock + #{count}, " +
            "version = version + 1 WHERE id = #{itemId} AND stock >= #{count} " +
            "AND status = 1 AND audit_status = 'APPROVED'")
    int reserveStock(@Param("itemId") Long itemId, @Param("count") Integer count);

    @Update("UPDATE t_item SET stock = stock + #{count}, frozen_stock = frozen_stock - #{count}, " +
            "version = version + 1 WHERE id = #{itemId} AND frozen_stock >= #{count}")
    int releaseStock(@Param("itemId") Long itemId, @Param("count") Integer count);

    @Update("UPDATE t_item SET frozen_stock = frozen_stock - #{count}, version = version + 1 " +
            "WHERE id = #{itemId} AND frozen_stock >= #{count}")
    int confirmStock(@Param("itemId") Long itemId, @Param("count") Integer count);

    @Update("UPDATE t_item SET stock = stock + #{count}, version = version + 1 " +
            "WHERE id = #{itemId}")
    int restoreRefundedStock(@Param("itemId") Long itemId, @Param("count") Integer count);

    // Confirm: 直接扣减冻结库存 (因为真实库存已经在 Try 阶段扣掉了)
    @Update("UPDATE t_item SET frozen_stock = frozen_stock - #{count} " +
            "WHERE id = #{itemId} AND frozen_stock >= #{count}")
    int confirmDeductStock(@Param("itemId") Long itemId, @Param("count") Integer count);

    // Cancel: 归还真实库存，扣减冻结库存 (回滚)
    @Update("UPDATE t_item SET stock = stock + #{count}, frozen_stock = frozen_stock - #{count} " +
            "WHERE id = #{itemId} AND frozen_stock >= #{count}")
    int cancelDeductStock(@Param("itemId") Long itemId, @Param("count") Integer count);
}
