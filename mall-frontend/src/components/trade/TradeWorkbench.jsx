import React, { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { useApp } from '../../context/AppContext';
import { tradeApi } from '../../api/tradeApi';
import { showToast } from '../../utils/feedback';
import { Pagination } from '../ui/Pagination';
import './TradeWorkbench.css';

const TABS = [
  { key: 'orders', label: '担保订单' },
  { key: 'seller', label: '卖家中心' },
  { key: 'account', label: '资金提现' },
  { key: 'admin', label: '运营审核' }
];

const statusText = {
  WAIT_PAY: '待支付',
  PAID: '已支付',
  DELIVERED: '已交付',
  CONFIRMED: '已确认',
  SETTLING: '结算中',
  SETTLED: '已结算',
  CANCELLED: '已关闭',
  REFUNDED: '已退款'
};

const auditText = {
  DRAFT: '草稿',
  PENDING: '待审核',
  APPROVED: '已上架',
  REJECTED: '已驳回'
};

const money = value => `¥${Number(value || 0).toFixed(2)}`;

const formatTime = value => (value ? String(value).replace('T', ' ').slice(0, 19) : '-');

const createClientToken = () => {
  if (typeof crypto !== 'undefined' && crypto.randomUUID) {
    return crypto.randomUUID().replace(/-/g, '');
  }
  return `${Date.now().toString(36)}${Math.random().toString(36).slice(2, 10)}`;
};

const orderSnapshot = order => {
  try {
    return typeof order.itemSnapshot === 'string'
      ? JSON.parse(order.itemSnapshot)
      : order.itemSnapshot;
  } catch {
    return {};
  }
};

const pageRecords = result => (result?.records ? result.records : result || []);

export const TradeWorkbench = ({ items = [] }) => {
  const { user, requireAuth } = useApp();
  const isAdmin = user?.role === 'ADMIN';
  const visibleTabs = useMemo(
    () => TABS.filter(tab => tab.key !== 'admin' || isAdmin),
    [isAdmin]
  );
  const [activeTab, setActiveTab] = useState('orders');
  const [orders, setOrders] = useState([]);
  const [ordersPage, setOrdersPage] = useState(1);
  const [ordersPageInfo, setOrdersPageInfo] = useState(null);
  const [secrets, setSecrets] = useState({});
  const [deliveries, setDeliveries] = useState({});
  const [deliveryInput, setDeliveryInput] = useState({});
  const [merchant, setMerchant] = useState(null);
  const [myItems, setMyItems] = useState([]);
  const [myItemsPage, setMyItemsPage] = useState(1);
  const [myItemsPageInfo, setMyItemsPageInfo] = useState(null);
  const [deposit, setDeposit] = useState(null);
  const [account, setAccount] = useState(null);
  const [withdrawals, setWithdrawals] = useState([]);
  const [withdrawalsPage, setWithdrawalsPage] = useState(1);
  const [withdrawalsPageInfo, setWithdrawalsPageInfo] = useState(null);
  const [merchants, setMerchants] = useState([]);
  const [pendingItems, setPendingItems] = useState([]);
  const [pendingDisputes, setPendingDisputes] = useState([]);
  const [pendingWithdrawals, setPendingWithdrawals] = useState([]);
  const [selectedDispute, setSelectedDispute] = useState(null);
  const [busy, setBusy] = useState('');
  const [error, setError] = useState('');
  const [newItem, setNewItem] = useState({
    itemName: '',
    price: '',
    stock: '0',
    assetType: 'CARD',
    deliveryMode: 'AUTO_CARD',
    sourceDescription: ''
  });
  const [cardInput, setCardInput] = useState('');
  const [cardItemId, setCardItemId] = useState('');
  const depositTokenRef = useRef(createClientToken());
  const withdrawTokenRef = useRef(createClientToken());
  const [depositAmount, setDepositAmount] = useState('100');
  const [withdrawAmount, setWithdrawAmount] = useState('');
  const [withdrawAccount, setWithdrawAccount] = useState('MOCK-ACCOUNT');
  const [merchantForm, setMerchantForm] = useState({
    merchantName: '',
    contactEmail: '',
    introduction: ''
  });

  const approvedItems = useMemo(
    () => items.filter(item => item.status === 1 && item.stock > 0),
    [items]
  );

  const runAction = useCallback(async (key, action, successMessage) => {
    setBusy(key);
    setError('');
    try {
      const result = await action();
      if (successMessage) {
        showToast('success', '操作成功', successMessage);
      }
      return result;
    } catch (e) {
      setError(e.message || '操作失败');
      showToast('error', '操作失败', e.message || '请稍后重试');
      return null;
    } finally {
      setBusy('');
    }
  }, []);

  const refreshOrders = useCallback(async (pageToLoad = 1) => {
    const data = await tradeApi.order.list({ page: pageToLoad });
    setOrders(pageRecords(data));
    setOrdersPageInfo(data);
    setOrdersPage(data?.page || pageToLoad);
  }, []);

  const refreshSeller = useCallback(async (pageToLoad = 1) => {
    if (!user) {
      setMerchant(null);
      setMyItems([]);
      return;
    }
    const currentMerchant = await tradeApi.merchant.me().catch(() => null);
    setMerchant(currentMerchant);
    if (currentMerchant?.status === 'APPROVED') {
      const [items, deposit] = await Promise.all([
        tradeApi.item.listMine({ page: pageToLoad }).catch(() => null),
        tradeApi.merchant.deposit(currentMerchant.id).catch(() => null)
      ]);
      setMyItems(pageRecords(items));
      setMyItemsPageInfo(items);
      setMyItemsPage(items?.page || pageToLoad);
      setDeposit(deposit);
    }
  }, [user]);

  const refreshAccount = useCallback(async (pageToLoad = 1) => {
    if (!user) {
      setAccount(null);
      setWithdrawals([]);
      return;
    }
    setAccount(await tradeApi.account.me().catch(() => null));
    const withdrawalList = await tradeApi.withdraw.list({ page: pageToLoad }).catch(() => null);
    setWithdrawals(pageRecords(withdrawalList));
    setWithdrawalsPageInfo(withdrawalList);
    setWithdrawalsPage(withdrawalList?.page || pageToLoad);
  }, [user]);

  const refreshAdmin = useCallback(async () => {
    if (!user || user.role !== 'ADMIN') {
      setMerchants([]);
      setPendingItems([]);
      setPendingDisputes([]);
      setPendingWithdrawals([]);
      return;
    }
    const [merchantList, itemList, disputeList, withdrawalList] = await Promise.all([
      tradeApi.merchant.list().catch(() => []),
      tradeApi.item.pendingAudit().catch(() => []),
      tradeApi.dispute.pending().catch(() => []),
      tradeApi.withdraw.pending().catch(() => [])
    ]);
    setMerchants(pageRecords(merchantList));
    setPendingItems(pageRecords(itemList));
    setPendingDisputes(pageRecords(disputeList));
    setPendingWithdrawals(pageRecords(withdrawalList));
  }, [user]);

  const loadTradeData = useCallback(() => {
    if (!user) return;
    refreshOrders().catch(() => setOrders([]));
    refreshSeller().catch(() => {});
    refreshAccount().catch(() => {});
    refreshAdmin().catch(() => {});
  }, [user, refreshOrders, refreshSeller, refreshAccount, refreshAdmin]);

  const changeOrdersPage = page => {
    setOrdersPage(page);
    refreshOrders(page).catch(() => setOrders([]));
  };

  const changeMyItemsPage = page => {
    setMyItemsPage(page);
    refreshSeller(page).catch(() => {});
  };

  const changeWithdrawalsPage = page => {
    setWithdrawalsPage(page);
    refreshAccount(page).catch(() => {});
  };

  useEffect(() => {
    loadTradeData();
  }, [loadTradeData]);

  const requireUser = callback => requireAuth(callback);

  const createOrder = item => requireUser(async () => {
    const order = await runAction(`create-${item.id}`, () => tradeApi.order.create(item.id, 1),
      '担保订单已创建');
    if (order) {
      await refreshOrders();
    }
  });

  const payOrder = order => requireUser(async () => {
    const paid = await runAction(`pay-${order.orderNo}`, async () => {
      await tradeApi.payment.start(order.paymentNo);
      return tradeApi.payment.pay(order.paymentNo);
    }, 'Mock 支付成功，资金已进入平台托管');
    if (paid) {
      await refreshOrders();
    }
  });

  const cancelOrder = order => requireUser(async () => {
    await runAction(`cancel-${order.orderNo}`, () => tradeApi.order.cancel(order.orderNo),
      '未支付订单已关闭');
    await refreshOrders();
  });

  const deliverOrder = order => requireUser(async () => {
    const content = deliveryInput[order.orderNo];
    await runAction(`deliver-${order.orderNo}`, () => tradeApi.order.deliver(order.orderNo, content),
      '交付完成');
    setDeliveryInput(prev => ({ ...prev, [order.orderNo]: '' }));
    await refreshOrders();
  });

  const viewDelivery = order => requireUser(async () => {
    const delivery = await runAction(`delivery-${order.orderNo}`,
      () => tradeApi.order.delivery(order.orderNo));
    if (delivery) {
      setDeliveries(prev => ({ ...prev, [order.orderNo]: delivery }));
    }
  });

  const viewSecrets = order => requireUser(async () => {
    const cards = await runAction(`secrets-${order.orderNo}`, () => tradeApi.order.secrets(order.orderNo));
    if (cards) {
      setSecrets(prev => ({ ...prev, [order.orderNo]: cards }));
    }
  });

  const confirmOrder = order => requireUser(async () => {
    await runAction(`confirm-${order.orderNo}`, () => tradeApi.order.confirm(order.orderNo),
      '订单已确认，进入结算冷却期');
    await refreshOrders();
  });

  const reviewOrder = order => requireUser(async () => {
    await runAction(`review-${order.orderNo}`, () => tradeApi.order.review(order.orderNo, 5,
      '担保交易体验良好'), '评价已提交');
    await refreshOrders();
  });

  const openDispute = order => requireUser(async () => {
    await runAction(`dispute-${order.orderNo}`, () => tradeApi.dispute.open(order.orderNo,
      'NOT_AS_DESCRIBED', '买家发起售后，申请平台介入', order.orderAmount), '售后已提交');
    await refreshOrders();
    await refreshAdmin();
  });

  const applyMerchant = () => requireUser(async () => {
    const created = await runAction('apply-merchant', () => tradeApi.merchant.apply({
      ...merchantForm,
      contactEmail: merchantForm.contactEmail || user.email
    }), '商家申请已提交');
    if (created) {
      setMerchant(created);
      await refreshAdmin();
    }
  });

  const payDeposit = () => requireUser(async () => {
    if (!merchant?.id) return;
    const deposit = await runAction('pay-deposit', () => tradeApi.merchant.payDeposit(
      merchant.id, depositAmount, depositTokenRef.current), 'Mock 保证金已缴纳');
    if (deposit) {
      depositTokenRef.current = createClientToken();
      setDeposit(deposit);
      showToast('success', '保证金余额', money(deposit.totalAmount));
    }
  });

  const createItem = () => requireUser(async () => {
    const created = await runAction('create-item', () => tradeApi.item.create(newItem), '商品草稿已创建');
    if (created) {
      setNewItem(prev => ({
        ...prev,
        itemName: '',
        price: '',
        sourceDescription: '',
        stock: prev.deliveryMode === 'AUTO_CARD' ? '0' : ''
      }));
      setMyItems(prev => [created, ...prev]);
    }
  });

  const submitItem = item => requireUser(async () => {
    await runAction(`submit-item-${item.id}`, () => tradeApi.item.submit(item.id), '商品已提交审核');
    setMyItems(await tradeApi.item.listMine().catch(() => []));
    await refreshAdmin();
  });

  const importCards = item => requireUser(async () => {
    const secrets = cardInput.split('\n').map(value => value.trim()).filter(Boolean);
    const count = await runAction(`cards-${item.id}`, () => tradeApi.item.importCards(item.id, secrets),
      '卡密已加密入库');
    if (count !== null) {
      setCardInput('');
      setMyItems(await tradeApi.item.listMine().catch(() => []));
    }
  });

  const applyWithdraw = () => requireUser(async () => {
    await runAction('withdraw-apply', () => tradeApi.withdraw.apply(
      withdrawAmount, withdrawAccount, withdrawTokenRef.current
    ),
      '提现申请已提交');
    withdrawTokenRef.current = createClientToken();
    setWithdrawAmount('');
    await refreshAccount();
    await refreshAdmin();
  });

  const auditMerchant = (id, action) => requireUser(async () => {
    await runAction(`merchant-audit-${id}`, () => tradeApi.merchant.audit(id, action), '商家审核完成');
    await refreshAdmin();
  });

  const auditItem = (id, action) => requireUser(async () => {
    await runAction(`item-audit-${id}`, () => tradeApi.item.audit(id, action), '商品审核完成');
    await refreshAdmin();
  });

  const auditWithdraw = (withdrawNo, approved) => requireUser(async () => {
    await runAction(`withdraw-audit-${withdrawNo}`, () => tradeApi.withdraw.audit(
      withdrawNo, approved), '提现审核完成');
    await refreshAccount();
    await refreshAdmin();
  });

  const viewDispute = dispute => requireUser(async () => {
    const detail = await runAction(`dispute-view-${dispute.disputeNo}`,
      () => tradeApi.dispute.evidence(dispute.disputeNo));
    if (detail) {
      setSelectedDispute(detail);
    }
  });

  const arbitrate = result => requireUser(async () => {
    if (!selectedDispute?.dispute) return;
    const disputeNo = selectedDispute.dispute.disputeNo;
    await runAction(`arbitrate-${disputeNo}`, () => tradeApi.dispute.arbitrate(
      disputeNo, result, '管理员根据平台证据完成仲裁'), '仲裁完成');
    setSelectedDispute(null);
    await refreshOrders();
    await refreshAdmin();
  });

  const renderOrderActions = order => {
    const isBuyer = order.buyerId === user?.id;
    const isSeller = order.sellerId === user?.id;
    const deliveryMode = orderSnapshot(order).deliveryMode;
    const actions = [];

    if (isBuyer && order.orderStatus === 'WAIT_PAY') {
      actions.push(
        <button key="pay" type="button" className="trade-action primary" disabled={busy === `pay-${order.orderNo}`}
          onClick={() => payOrder(order)}>Mock 支付</button>,
        <button key="cancel" type="button" className="trade-action" disabled={busy === `cancel-${order.orderNo}`}
          onClick={() => cancelOrder(order)}>取消订单</button>
      );
    }
    if (isSeller && order.orderStatus === 'PAID') {
      const isManualDelivery = deliveryMode === 'MANUAL_DELIVERY';
      actions.push(
        isManualDelivery ? (
          <input key="deliver-input" className="trade-input" value={deliveryInput[order.orderNo] || ''}
            placeholder="填写交付说明" onChange={event => setDeliveryInput(prev => ({
              ...prev,
              [order.orderNo]: event.target.value
            }))} />
        ) : null,
        <button key="deliver" type="button" className="trade-action primary"
          disabled={busy === `deliver-${order.orderNo}`
            || (isManualDelivery && !(deliveryInput[order.orderNo] || '').trim())}
          onClick={() => deliverOrder(order)}>交付资产</button>
      );
    }
    if (isBuyer && ['DELIVERED', 'CONFIRMED', 'SETTLING', 'SETTLED'].includes(order.orderStatus)
      && !['OPEN', 'NEGOTIATING', 'ARBITRATING', 'APPEALED'].includes(order.disputeStatus)) {
      actions.push(
        <button key="delivery" type="button" className="trade-action"
          disabled={busy === `delivery-${order.orderNo}`} onClick={() => viewDelivery(order)}>
          查看交付
        </button>,
        deliveryMode === 'AUTO_CARD' ? (
          <button key="secrets" type="button" className="trade-action"
            disabled={busy === `secrets-${order.orderNo}`} onClick={() => viewSecrets(order)}>查看卡密</button>
        ) : null,
        <button key="confirm" type="button" className="trade-action primary"
          disabled={busy === `confirm-${order.orderNo}`} onClick={() => confirmOrder(order)}>确认收货</button>,
        order.orderStatus === 'DELIVERED' ? (
          <button key="dispute" type="button" className="trade-action danger"
          disabled={busy === `dispute-${order.orderNo}`} onClick={() => openDispute(order)}>发起售后</button>
        ) : null
      );
    }
    if (isBuyer && order.orderStatus === 'SETTLED') {
      actions.push(
        <button key="review" type="button" className="trade-action"
          disabled={busy === `review-${order.orderNo}`} onClick={() => reviewOrder(order)}>5 星评价</button>
      );
    }

    return actions.length ? <div className="trade-actions">{actions}</div> : null;
  };

  const renderOrders = () => (
    <div className="trade-grid">
      <section className="trade-panel">
        <h3 className="trade-panel-title">可购买资产</h3>
        <p className="trade-panel-desc">仅展示已审核且有余量的商品</p>
        <div className="trade-list">
          {approvedItems.length === 0 ? <div className="trade-empty">暂无可购买资产</div> : approvedItems.map(item => (
            <div className="trade-row" key={item.id}>
              <div className="trade-row-main">
                <span className="trade-row-title">{item.itemName}</span>
                <strong>{money(item.price)}</strong>
              </div>
              <button type="button" className="trade-action primary"
                disabled={busy === `create-${item.id}`} onClick={() => createOrder(item)}>
                创建担保订单
              </button>
            </div>
          ))}
        </div>
        <Pagination page={ordersPage} pageSize={ordersPageInfo?.pageSize}
          total={ordersPageInfo?.total} hasMore={ordersPageInfo?.hasMore}
          onPageChange={changeOrdersPage} />
      </section>

      <section className="trade-panel">
        <h3 className="trade-panel-title">我的担保订单</h3>
        <p className="trade-panel-desc">支付、交付、确认、售后和结算状态集中在一张列表</p>
        <div className="trade-list">
          {orders.length === 0 ? <div className="trade-empty">暂无订单</div> : orders.map(order => (
            <div className="trade-row" key={order.orderNo}>
              <div className="trade-row-main">
                <div>
                  <div className="trade-row-title">{order.orderNo}</div>
                  <div className="trade-row-meta">
                    {orderSnapshot(order).itemName ? `${orderSnapshot(order).itemName} · ` : ''}
                    {order.quantity} 件 · {money(order.orderAmount)} ·
                    支付截止 {formatTime(order.payDeadline)}
                  </div>
                </div>
                <span className={`trade-status ${order.orderStatus === 'SETTLED' ? 'success' : ''} ${order.orderStatus === 'CANCELLED' || order.orderStatus === 'REFUNDED' ? 'danger' : ''}`}>
                  {statusText[order.orderStatus] || order.orderStatus}
                </span>
              </div>
              <div className="trade-row-meta">
                托管 {order.escrowStatus || 'NONE'} · 售后 {order.disputeStatus || 'NONE'} ·
                买家 {order.buyerId} / 卖家 {order.sellerId}
              </div>
              {renderOrderActions(order)}
              {secrets[order.orderNo]?.length ? (
                <div className="trade-secret">
                  {secrets[order.orderNo].map(secret => (
                    <div key={secret.id}>{secret.secretPlain}</div>
                  ))}
                </div>
              ) : null}
              {deliveries[order.orderNo] ? (
                <div className="trade-secret">{deliveries[order.orderNo].deliveryContent}</div>
              ) : null}
            </div>
          ))}
        </div>
      </section>
    </div>
  );

  const renderSeller = () => (
    <div className="trade-grid">
      <section className="trade-panel">
        <h3 className="trade-panel-title">商家入驻</h3>
        <p className="trade-panel-desc">学习项目使用审核和保证金模拟商家准入</p>
        {merchant?.status === 'APPROVED' ? (
          <div className="trade-list">
            <div className="trade-row">
              <span className="trade-row-title">{merchant.merchantName}</span>
              <span className="trade-row-meta">商家 ID：{merchant.id} · 状态：已通过</span>
            </div>
            {deposit ? (
              <div className="trade-row-meta">
                保证金总额：{money(deposit.totalAmount)} · 可用：
                {money(Number(deposit.totalAmount || 0) - Number(deposit.frozenAmount || 0)
                  - Number(deposit.deductedAmount || 0))}
              </div>
            ) : null}
            <div className="trade-form">
              <div className="trade-field">
                <label className="trade-label" htmlFor="depositAmount">Mock 保证金金额</label>
                <input id="depositAmount" className="trade-input" value={depositAmount}
                  onChange={event => setDepositAmount(event.target.value)} />
              </div>
              <button type="button" className="trade-action primary" disabled={busy === 'pay-deposit'}
                onClick={payDeposit}>缴纳保证金</button>
            </div>
          </div>
        ) : (
          <div className="trade-form">
            <div className="trade-field">
              <label className="trade-label" htmlFor="merchantName">商家名称</label>
              <input id="merchantName" className="trade-input" value={merchantForm.merchantName}
                onChange={event => setMerchantForm(prev => ({ ...prev, merchantName: event.target.value }))} />
            </div>
            <div className="trade-field">
              <label className="trade-label" htmlFor="contactEmail">联系邮箱</label>
              <input id="contactEmail" className="trade-input" value={merchantForm.contactEmail}
                placeholder={user?.email || ''} onChange={event => setMerchantForm(prev => ({
                  ...prev,
                  contactEmail: event.target.value
                }))} />
            </div>
            <div className="trade-field">
              <label className="trade-label" htmlFor="introduction">商家介绍</label>
              <textarea id="introduction" className="trade-textarea" value={merchantForm.introduction}
                onChange={event => setMerchantForm(prev => ({ ...prev, introduction: event.target.value }))} />
            </div>
            <button type="button" className="trade-action primary" disabled={busy === 'apply-merchant'
              || !merchantForm.merchantName} onClick={applyMerchant}>提交商家申请</button>
            {merchant ? <div className="trade-hint">当前商家状态：{merchant.status}</div> : null}
          </div>
        )}
      </section>

      <section className="trade-panel">
        <h3 className="trade-panel-title">资产挂售</h3>
        <p className="trade-panel-desc">创建草稿、导入卡密、提交审核后再上架</p>
        {merchant?.status !== 'APPROVED' ? <div className="trade-empty">商家审核通过后可挂售资产</div> : (
          <>
            <div className="trade-form">
              <div className="trade-field">
                <label className="trade-label" htmlFor="itemName">资产名称</label>
                <input id="itemName" className="trade-input" value={newItem.itemName}
                  onChange={event => setNewItem(prev => ({ ...prev, itemName: event.target.value }))} />
              </div>
              <div className="trade-field">
                <label className="trade-label" htmlFor="price">单价</label>
                <input id="price" className="trade-input" value={newItem.price}
                  onChange={event => setNewItem(prev => ({ ...prev, price: event.target.value }))} />
              </div>
              <div className="trade-field">
                <label className="trade-label" htmlFor="assetType">资产类型</label>
                <select id="assetType" className="trade-input" value={newItem.assetType}
                  onChange={event => setNewItem(prev => {
                    const assetType = event.target.value;
                    const deliveryMode = assetType === 'CARD' ? prev.deliveryMode : 'MANUAL_DELIVERY';
                    return {
                      ...prev,
                      assetType,
                      deliveryMode,
                      stock: deliveryMode === 'AUTO_CARD' ? '0' : prev.stock
                    };
                  })}>
                  <option value="CARD">卡密</option>
                  <option value="VIRTUAL_SKIN">虚拟皮肤</option>
                  <option value="GAME_ITEM">游戏道具</option>
                </select>
              </div>
              <div className="trade-field">
                <label className="trade-label" htmlFor="deliveryMode">交付方式</label>
                <select id="deliveryMode" className="trade-input" value={newItem.deliveryMode}
                  onChange={event => setNewItem(prev => ({
                    ...prev,
                    deliveryMode: event.target.value,
                    stock: event.target.value === 'AUTO_CARD' ? '0' : prev.stock
                  }))}>
                  <option value="AUTO_CARD">自动卡密</option>
                  <option value="MANUAL_DELIVERY">手动交付</option>
                </select>
              </div>
              <div className="trade-field">
                <label className="trade-label" htmlFor="stock">
                  {newItem.deliveryMode === 'AUTO_CARD' ? '卡密库存' : '手动交付库存'}
                </label>
                <input id="stock" className="trade-input" value={newItem.stock}
                  disabled={newItem.deliveryMode === 'AUTO_CARD'} readOnly={newItem.deliveryMode === 'AUTO_CARD'}
                  onChange={event => setNewItem(prev => ({ ...prev, stock: event.target.value }))} />
              </div>
              <button type="button" className="trade-action primary" disabled={busy === 'create-item'
                || !newItem.itemName || !newItem.price
                || (newItem.deliveryMode === 'MANUAL_DELIVERY' && Number(newItem.stock) <= 0)}
                onClick={createItem}>创建商品草稿</button>
            </div>
            <div className="trade-list">
              {myItems.length === 0 ? <div className="trade-empty">暂无挂售商品</div> : myItems.map(item => (
                <div className="trade-row" key={item.id}>
                  <div className="trade-row-main">
                    <span className="trade-row-title">{item.itemName}</span>
                    <span className={`trade-status ${item.auditStatus === 'APPROVED' ? 'success' : item.auditStatus === 'PENDING' ? 'pending' : ''}`}>
                      {auditText[item.auditStatus] || item.auditStatus}
                    </span>
                  </div>
                  <div className="trade-row-meta">{money(item.price)} · 库存 {item.stock}</div>
                  {item.auditStatus === 'DRAFT' || item.auditStatus === 'REJECTED' ? (
                    <button type="button" className="trade-action"
                      disabled={busy === `submit-item-${item.id}`} onClick={() => submitItem(item)}>
                      提交审核
                    </button>
                  ) : null}
                </div>
              ))}
            </div>
            <Pagination page={myItemsPage} pageSize={myItemsPageInfo?.pageSize}
              total={myItemsPageInfo?.total} hasMore={myItemsPageInfo?.hasMore}
              onPageChange={changeMyItemsPage} />
            <div className="trade-form">
              <div className="trade-field">
                <label className="trade-label" htmlFor="cardInput">批量导入卡密</label>
                <textarea id="cardInput" className="trade-textarea" value={cardInput}
                  placeholder="每行一个卡密" onChange={event => setCardInput(event.target.value)} />
              </div>
              <div className="trade-actions">
                <select className="trade-input" value={cardItemId}
                  onChange={event => setCardItemId(event.target.value)}
                  aria-label="选择导入卡密的商品">
                  <option value="">选择商品</option>
                  {myItems.filter(item => item.auditStatus !== 'APPROVED'
                    && item.deliveryMode === 'AUTO_CARD' && item.assetType === 'CARD').map(item => (
                    <option key={item.id} value={item.id}>{item.itemName}</option>
                  ))}
                </select>
                <button type="button" className="trade-action"
                  disabled={busy === `cards-${cardItemId}` || !cardItemId || !cardInput.trim()}
                  onClick={() => {
                    const target = myItems.find(item => String(item.id) === String(cardItemId));
                    if (target) {
                      importCards(target);
                    }
                  }}>导入卡密</button>
              </div>
              <div className="trade-hint">卡密会先加密入库；商品审核通过后用于订单预留和交付。</div>
            </div>
          </>
        )}
      </section>
    </div>
  );

  const renderAccount = () => (
    <div className="trade-grid">
      <section className="trade-panel">
        <h3 className="trade-panel-title">资金账户</h3>
        <p className="trade-panel-desc">平台内部账本，不接真实支付通道</p>
        {account ? (
          <div className="trade-balance">
            <div className="trade-balance-item">
              <div className="trade-balance-label">可用余额</div>
              <div className="trade-balance-value">{money(account.availableAmount)}</div>
            </div>
            <div className="trade-balance-item">
              <div className="trade-balance-label">待结算</div>
              <div className="trade-balance-value">{money(account.pendingSettleAmount)}</div>
            </div>
            <div className="trade-balance-item">
              <div className="trade-balance-label">冻结金额</div>
              <div className="trade-balance-value">{money(account.frozenAmount)}</div>
            </div>
          </div>
        ) : <div className="trade-empty">登录后查看资金账户</div>}
        <div className="trade-form">
          <div className="trade-field">
            <label className="trade-label" htmlFor="withdrawAmount">提现金额</label>
            <input id="withdrawAmount" className="trade-input" value={withdrawAmount}
              onChange={event => setWithdrawAmount(event.target.value)} />
          </div>
          <div className="trade-field">
            <label className="trade-label" htmlFor="withdrawAccount">Mock 收款账户</label>
            <input id="withdrawAccount" className="trade-input" value={withdrawAccount}
              onChange={event => setWithdrawAccount(event.target.value)} />
          </div>
          <button type="button" className="trade-action primary" disabled={busy === 'withdraw-apply'
            || !withdrawAmount} onClick={applyWithdraw}>申请提现</button>
        </div>
      </section>

      <section className="trade-panel">
        <h3 className="trade-panel-title">提现记录</h3>
        <p className="trade-panel-desc">管理员审核通过后执行模拟打款</p>
        <div className="trade-list">
          {withdrawals.length === 0 ? <div className="trade-empty">暂无提现记录</div> : withdrawals.map(item => (
            <div className="trade-row" key={item.withdrawNo || item.id}>
              <div className="trade-row-main">
                <span className="trade-row-title">{item.withdrawNo}</span>
                <strong>{money(item.amount)}</strong>
              </div>
              <div className="trade-row-meta">状态 {item.status} · 申请时间 {formatTime(item.createdTime)}</div>
            </div>
          ))}
        </div>
        <Pagination page={withdrawalsPage} pageSize={withdrawalsPageInfo?.pageSize}
          total={withdrawalsPageInfo?.total} hasMore={withdrawalsPageInfo?.hasMore}
          onPageChange={changeWithdrawalsPage} />
      </section>
    </div>
  );

  const renderAdmin = () => (
    <div className="trade-grid">
      <section className="trade-panel">
        <h3 className="trade-panel-title">商家与商品审核</h3>
        <p className="trade-panel-desc">管理员准入商家，商品审核通过后进入买家列表</p>
        <div className="trade-list">
          {merchants.filter(item => item.status === 'SUBMITTED').map(item => (
            <div className="trade-row" key={`m-${item.id}`}>
              <div className="trade-row-main">
                <span className="trade-row-title">{item.merchantName}</span>
                <span className="trade-status pending">待审核</span>
              </div>
              <div className="trade-actions">
                <button type="button" className="trade-action primary"
                  disabled={busy === `merchant-audit-${item.id}`} onClick={() => auditMerchant(item.id, 'APPROVE')}>
                  通过
                </button>
                <button type="button" className="trade-action"
                  disabled={busy === `merchant-audit-${item.id}`} onClick={() => auditMerchant(item.id, 'REJECT')}>
                  拒绝
                </button>
              </div>
            </div>
          ))}
          {pendingItems.map(item => (
            <div className="trade-row" key={`i-${item.id}`}>
              <div className="trade-row-main">
                <span className="trade-row-title">{item.itemName}</span>
                <span className="trade-status pending">待审核</span>
              </div>
              <div className="trade-actions">
                <button type="button" className="trade-action primary"
                  disabled={busy === `item-audit-${item.id}`} onClick={() => auditItem(item.id, 'APPROVE')}>
                  上架
                </button>
                <button type="button" className="trade-action"
                  disabled={busy === `item-audit-${item.id}`} onClick={() => auditItem(item.id, 'REJECT')}>
                  驳回
                </button>
              </div>
            </div>
          ))}
          {merchants.every(item => item.status !== 'SUBMITTED') && pendingItems.length === 0 ? (
            <div className="trade-empty">暂无待审核申请</div>
          ) : null}
        </div>
      </section>

      <section className="trade-panel">
        <h3 className="trade-panel-title">售后与提现处理</h3>
        <p className="trade-panel-desc">阶段一仲裁支持全额退款或全额放款</p>
        <div className="trade-list">
          {pendingDisputes.map(dispute => (
            <div className="trade-row" key={`d-${dispute.disputeNo}`}>
              <div className="trade-row-main">
                <span className="trade-row-title">{dispute.disputeNo}</span>
                <span className="trade-status pending">仲裁中</span>
              </div>
              <div className="trade-row-meta">
                订单 {dispute.orderNo} · 申请退款 {money(dispute.proposedRefundAmount)}
              </div>
              <div className="trade-actions">
                <button type="button" className="trade-action"
                  disabled={busy === `dispute-view-${dispute.disputeNo}`}
                  onClick={() => viewDispute(dispute)}>查看证据</button>
                <button type="button" className="trade-action danger"
                  disabled={busy === `arbitrate-${dispute.disputeNo}`}
                  onClick={() => arbitrate('REFUND_ALL')}>全额退款</button>
                <button type="button" className="trade-action primary"
                  disabled={busy === `arbitrate-${dispute.disputeNo}`}
                  onClick={() => arbitrate('RELEASE_ALL')}>全额放款</button>
              </div>
            </div>
          ))}
          {pendingWithdrawals.map(item => (
            <div className="trade-row" key={`w-${item.withdrawNo || item.id}`}>
              <div className="trade-row-main">
                <span className="trade-row-title">{item.withdrawNo}</span>
                <span className="trade-status pending">待审核</span>
              </div>
              <div className="trade-actions">
                <button type="button" className="trade-action primary"
                  disabled={busy === `withdraw-audit-${item.withdrawNo}`}
                  onClick={() => auditWithdraw(item.withdrawNo, true)}>通过打款</button>
                <button type="button" className="trade-action"
                  disabled={busy === `withdraw-audit-${item.withdrawNo}`}
                  onClick={() => auditWithdraw(item.withdrawNo, false)}>拒绝</button>
              </div>
            </div>
          ))}
          {pendingDisputes.length === 0 && pendingWithdrawals.length === 0 ? (
            <div className="trade-empty">暂无待处理事项</div>
          ) : null}
        </div>
      </section>
    </div>
  );

  return (
    <section className="trade-workbench layout-container page-section" id="tradeSection">
      <div className="section-header">
        <div className="section-title-wrap">
          <h2 className="section-title">担保交易工作台</h2>
          <p className="section-desc">平台托管资金、卖家交付、买家确认、结算与仲裁演示</p>
        </div>
      </div>
      <div className="trade-tabs" role="tablist">
        {visibleTabs.map(tab => (
          <button key={tab.key} type="button" role="tab" aria-selected={activeTab === tab.key}
            className={`trade-tab ${activeTab === tab.key ? 'active' : ''}`}
            onClick={() => setActiveTab(tab.key)}>{tab.label}</button>
        ))}
      </div>
      {error ? <div className="trade-hint">{error}</div> : null}
      {activeTab === 'orders' ? renderOrders() : null}
      {activeTab === 'seller' ? renderSeller() : null}
      {activeTab === 'account' ? renderAccount() : null}
      {activeTab === 'admin' ? renderAdmin() : null}
    </section>
  );
};
