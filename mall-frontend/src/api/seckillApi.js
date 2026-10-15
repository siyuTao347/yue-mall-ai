import { API_BASE, request } from './client';

export const seckillApi = {
  // 获取动态防刷秒杀 Token
  async getPath(itemId, userId) {
    const query = userId ? `?itemId=${itemId}&userId=${userId}` : `?itemId=${itemId}`;
    const res = await request(`${API_BASE.ITEM}/api/seckill/getPath${query}`, {
      method: 'POST'
    });
    if (!res || !res.pathToken) {
      throw new Error(res?.msg || '获取秒杀令牌失败');
    }
    return res.pathToken;
  },

  // 执行秒杀下单 (带动态 Token 校验)
  async doSeckill(pathToken, itemId, userId) {
    const query = userId ? `?itemId=${itemId}&userId=${userId}` : `?itemId=${itemId}`;
    return await request(`${API_BASE.ITEM}/api/seckill/${pathToken}/doSeckill${query}`, {
      method: 'POST'
    });
  }
};
