package com.example.item.payment.mock;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface MockNotificationOutboxMapper extends BaseMapper<MockNotificationOutbox> {

    @Select("SELECT * FROM t_mock_notification_outbox WHERE status IN ('PENDING', 'FAILED') " +
            "AND next_send_at <= #{now} ORDER BY id ASC LIMIT #{limit}")
    List<MockNotificationOutbox> selectPendingOutbox(@Param("now") LocalDateTime now, @Param("limit") int limit);
}
