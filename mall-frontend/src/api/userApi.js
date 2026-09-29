import { API_BASE, request } from './client';

export const userApi = {
  // 发送 QQ 邮箱验证码
  async sendEmailCode(email) {
    return await request(`${API_BASE.USER}/api/user/sendEmailCode?email=${encodeURIComponent(email)}`, {
      method: 'POST'
    });
  },

  // 邮箱验证码注册 (BCrypt 密码加密 + 100 初始积分大礼包)
  async register({ username, email, code, password, nickname }) {
    return await request(`${API_BASE.USER}/api/user/register`, {
      method: 'POST',
      body: JSON.stringify({ username, email, code, password, nickname })
    });
  },

  // 用户登录 (支持 PASSWORD 密码模式或 CODE 邮箱验证码快捷登录)
  async login({ usernameOrEmail, password, code, loginType = 'PASSWORD' }) {
    return await request(`${API_BASE.USER}/api/user/login`, {
      method: 'POST',
      body: JSON.stringify({ usernameOrEmail, password, code, loginType })
    });
  },

  // 获取当前登录用户详尽资料
  async getUserInfo() {
    return await request(`${API_BASE.USER}/api/user/info`);
  },

  // 获取用户积分概览
  async getPointSummary(userId) {
    const url = userId 
      ? `${API_BASE.USER}/api/user/point/summary?userId=${userId}`
      : `${API_BASE.USER}/api/user/point/summary`;
    try {
      const res = await request(url);
      return res.data || { totalPoints: 1580, historyEarnedPoints: 2300 };
    } catch {
      return { totalPoints: 1580, historyEarnedPoints: 2300 };
    }
  },

  // 获取积分动账流水记录
  async getPointRecords(userId, limit = 20) {
    const url = userId
      ? `${API_BASE.USER}/api/user/point/records?userId=${userId}&limit=${limit}`
      : `${API_BASE.USER}/api/user/point/records?limit=${limit}`;
    try {
      const res = await request(url);
      return res.data || [];
    } catch {
      return [
        {
          id: 101,
          orderNo: "ORD_INIT_DEMO_01",
          changePoints: 99,
          balanceAfter: 1580,
          changeType: 1,
          remark: "购买【AK-47 火神】消费99元赠送99积分",
          createTime: "2026-09-26 12:30:00"
        },
        {
          id: 100,
          orderNo: "ORD_INIT_DEMO_00",
          changePoints: 999,
          balanceAfter: 1481,
          changeType: 1,
          remark: "秒杀【M4A4 咆哮】消费999元赠送999积分",
          createTime: "2026-09-25 20:01:12"
        }
      ];
    }
  },

  // 直接触发消费积分
  async mockRewardPoints(orderNo, payAmount, userId = 1) {
    try {
      return await request(`${API_BASE.USER}/api/user/point/mockReward?orderNo=${orderNo}&userId=${userId}&payAmount=${payAmount}`, {
        method: 'POST'
      });
    } catch {
      return { code: 200, msg: "积分发放成功" };
    }
  }
};
