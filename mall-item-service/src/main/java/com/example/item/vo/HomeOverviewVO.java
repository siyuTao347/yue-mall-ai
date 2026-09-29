package com.example.item.vo;

import com.example.item.entity.Banner;
import com.example.item.entity.Item;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class HomeOverviewVO {
    private Long serverTime;
    private List<Banner> banners;
    private List<String> tickers;
    private List<SeckillSessionVO> sessions;
    private SeckillSessionVO currentSession;
    private List<Item> hotItems;
}
