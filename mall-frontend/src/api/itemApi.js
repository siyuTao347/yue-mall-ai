import { API_BASE, request } from './client';
import { DEMO_FALLBACK } from './mockData';

export const itemApi = {
  // 获取服务器校准时间
  async getServerTime() {
    try {
      const start = Date.now();
      const res = await request(`${API_BASE.ITEM}/api/system/time`);
      const rtt = Date.now() - start;
      return { serverTime: res.serverTime, rtt };
    } catch {
      return { serverTime: Date.now(), rtt: 0 };
    }
  },

  // 获取首页聚合数据
  async getHomeOverview() {
    try {
      const res = await request(`${API_BASE.ITEM}/api/home/overview`);
      if (res && res.code === 200 && res.data) {
        return {
          ...res.data,
          sessions: [],
          tickers: normalizeTickerText(res.data.tickers || []),
          banners: (res.data.banners || []).map(banner => ({
            ...banner,
            title: normalizeHeadline(banner.title),
            desc: normalizeNotice(banner.desc || ''),
            badgeText: normalizeTag(banner.badgeText)
          }))
        };
      }
      return {
        ...DEMO_FALLBACK,
        sessions: []
      };
    } catch {
      return {
        ...DEMO_FALLBACK,
        sessions: []
      };
    }
  },

  // 获取秒杀场次及商品
  async getSeckillSessions(sessionId) {
    try {
      const url = sessionId
        ? `${API_BASE.ITEM}/api/seckill/sessions?sessionId=${sessionId}`
        : `${API_BASE.ITEM}/api/seckill/sessions`;
      const res = await request(url);
      if (res && res.code === 200 && res.data) {
        return {
          serverTime: res.serverTime,
          sessions: res.data
        };
      }
      return { serverTime: null, sessions: [] };
    } catch {
      return { serverTime: null, sessions: [] };
    }
  },

  // 获取商品详情
  async getItemDetail(itemId) {
    try {
      const res = await request(`${API_BASE.ITEM}/api/item/${itemId}`);
      return res.data;
    } catch {
      return DEMO_FALLBACK.catalogItems.find(i => i.id === itemId);
    }
  }
};

const stripDecorativeChars = text => Array.from(String(text || '')).filter(char => {
  const codePoint = char.codePointAt(0);
  const isEmoji = (codePoint >= 0x1F300 && codePoint <= 0x1FAFF) ||
    (codePoint >= 0x2600 && codePoint <= 0x27BF) ||
    codePoint === 0xFE0F;
  return !isEmoji;
}).join('');

const normalizeText = text => stripDecorativeChars(text)
  .replace(/(赶紧|火速|疯狂|劲爆|炸裂|逆天|史诗级|殿堂级|超级|黄金|重磅|镇场|爆款|神作|绝版)+/g, '')
  .replace(/[【】〔〕]/g, '')
  .replace(/!+/g, '。')
  .replace(/。\s*。/g, '。')
  .replace(/\s{2,}/g, ' ')
  .trim();

const normalizeTag = text => normalizeNotice(text) || '今日推荐';

const normalizeTickerText = tickers => tickers.map(ticker => {
  const text = normalizeNotice(ticker);
  return text.length > 34 ? `${text.slice(0, 33)}…` : text;
});

const normalizeNotice = text => {
  const normalized = normalizeText(text);
  return normalized ? normalized.replace(/。$/, '') : '商城活动更新中';
};

const normalizeHeadline = text => {
  const normalized = normalizeText(text);
  if (!normalized) return '精选专场';
  return normalized.length > 16 ? `${normalized.slice(0, 15)}…` : normalized;
};
