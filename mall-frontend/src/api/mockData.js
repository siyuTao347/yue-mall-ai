export const DEMO_FALLBACK = {
  banners: [
    {
      id: 1,
      title: '今晚 8 点精选专场',
      badgeText: '今日主推',
      imageUrl: 'https://images.unsplash.com/photo-1550745165-9bc0b252726f?auto=format&fit=crop&w=1200&q=80',
      targetUrl: '#seckillSection',
      desc: '多款热门饰品限量开抢，先到先得。'
    },
    {
      id: 2,
      title: '热门饰品低至 1 折',
      badgeText: '限时优惠',
      imageUrl: 'https://images.unsplash.com/photo-1511512578047-dfb367046420?auto=format&fit=crop&w=1200&q=80',
      targetUrl: '#seckillSection',
      desc: '每天多场秒杀，场次开启后即可下单。'
    },
    {
      id: 3,
      title: '消费 1 元得 1 积分',
      badgeText: '会员权益',
      imageUrl: 'https://images.unsplash.com/photo-1579783900882-c0d3dad7b119?auto=format&fit=crop&w=1200&q=80',
      targetUrl: '#catalogSection',
      desc: '积分自动入账，可在我的积分中查看明细。'
    }
  ],
  tickers: [
    '20:00 场次即将开启，记得提前进入会场',
    '会员消费 1 元得 1 积分，支付后自动入账',
    '14:00 场次部分商品库存紧张',
    '热门饰品每日上新，收藏页面随时查看'
  ],
  sessions: [
    {
      sessionId: 1,
      sessionName: '10:00 场',
      status: 2,
      statusText: '已结束',
      countDownMs: 0,
      startTime: Date.now() - 3600000 * 3,
      endTime: Date.now() - 3600000,
      items: []
    },
    {
      sessionId: 2,
      sessionName: '14:00 场',
      status: 1,
      statusText: '抢购中',
      countDownMs: 7200000,
      startTime: Date.now() - 1800000,
      endTime: Date.now() + 7200000,
      items: [
        {
          id: 101,
          itemId: 1,
          itemName: 'AK-47 | 火神',
          subTitle: '久经沙场 | 磨损 0.18',
          imageUrl: 'https://images.unsplash.com/photo-1542751371-adc38448a05e?auto=format&fit=crop&w=800&q=80',
          originalPrice: 99,
          seckillPrice: 9.9,
          seckillStock: 100,
          remainStock: 36,
          percent: 64,
          isSoldOut: false,
          limitPerUser: 1
        },
        {
          id: 102,
          itemId: 2,
          itemName: 'M4A4 | 咆哮',
          subTitle: '崭新出厂 | 违禁级',
          imageUrl: 'https://images.unsplash.com/photo-1511512578047-dfb367046420?auto=format&fit=crop&w=800&q=80',
          originalPrice: 999,
          seckillPrice: 99,
          seckillStock: 50,
          remainStock: 12,
          percent: 76,
          isSoldOut: false,
          limitPerUser: 1
        },
        {
          id: 103,
          itemId: 4,
          itemName: '蝴蝶刀 | 渐变大理石',
          subTitle: '崭新出厂 | 满色渐变',
          imageUrl: 'https://images.unsplash.com/photo-1579783900882-c0d3dad7b119?auto=format&fit=crop&w=800&q=80',
          originalPrice: 6599,
          seckillPrice: 699,
          seckillStock: 10,
          remainStock: 4,
          percent: 60,
          isSoldOut: false,
          limitPerUser: 1
        }
      ]
    },
    {
      sessionId: 3,
      sessionName: '20:00 场',
      status: 0,
      statusText: '即将开始',
      countDownMs: 14400000,
      startTime: Date.now() + 14400000,
      endTime: Date.now() + 21600000,
      items: [
        {
          id: 104,
          itemId: 3,
          itemName: 'AWP | 巨龙传说（纪念品）',
          subTitle: '崭新出厂 | 比赛纪念',
          imageUrl: 'https://images.unsplash.com/photo-1550745165-9bc0b252726f?auto=format&fit=crop&w=800&q=80',
          originalPrice: 12999,
          seckillPrice: 999,
          seckillStock: 5,
          remainStock: 5,
          percent: 0,
          isSoldOut: false,
          limitPerUser: 1
        },
        {
          id: 105,
          itemId: 5,
          itemName: '运动手套 | 迈阿密风云',
          subTitle: '略有磨损 | 渐变配色',
          imageUrl: 'https://images.unsplash.com/photo-1550751827-4bd374c3f58b?auto=format&fit=crop&w=800&q=80',
          originalPrice: 8888,
          seckillPrice: 888,
          seckillStock: 8,
          remainStock: 8,
          percent: 0,
          isSoldOut: false,
          limitPerUser: 1
        }
      ]
    }
  ],
  catalogItems: [
    {
      id: 1,
      itemName: 'AK-47 | 火神（久经沙场）',
      subTitle: '经典涂装 | 蓝白配色',
      price: 99,
      stock: 9100,
      category: '步枪',
      imageUrl: 'https://images.unsplash.com/photo-1542751371-adc38448a05e?auto=format&fit=crop&w=800&q=80'
    },
    {
      id: 2,
      itemName: 'M4A4 | 咆哮（崭新出厂）',
      subTitle: '违禁级 | 火红图案',
      price: 999,
      stock: 50,
      category: '步枪',
      imageUrl: 'https://images.unsplash.com/photo-1511512578047-dfb367046420?auto=format&fit=crop&w=800&q=80'
    },
    {
      id: 3,
      itemName: 'AWP | 巨龙传说（纪念品级）',
      subTitle: '比赛纪念 | 金色印花',
      price: 12999,
      stock: 20,
      category: '狙击枪',
      imageUrl: 'https://images.unsplash.com/photo-1550745165-9bc0b252726f?auto=format&fit=crop&w=800&q=80'
    },
    {
      id: 4,
      itemName: '蝴蝶刀 | 渐变大理石',
      subTitle: '多彩纹理 | 蝴蝶刀型',
      price: 6599,
      stock: 30,
      category: '匕首',
      imageUrl: 'https://images.unsplash.com/photo-1579783900882-c0d3dad7b119?auto=format&fit=crop&w=800&q=80'
    },
    {
      id: 5,
      itemName: '运动手套 | 迈阿密风云',
      subTitle: '略有磨损 | 霓虹配色',
      price: 8888,
      stock: 25,
      category: '手套',
      imageUrl: 'https://images.unsplash.com/photo-1550751827-4bd374c3f58b?auto=format&fit=crop&w=800&q=80'
    }
  ]
};
