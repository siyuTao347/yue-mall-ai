import { useCallback, useEffect, useRef, useState } from 'react';
import { customerAdminApi as api } from '../../api/customerServiceApi';
import { CustomerStaffWorkspace } from './CustomerStaffWorkspace';
import './CustomerService.css';

export const CustomerServiceAdmin = () => {
  const [section, setSection] = useState('workspace');
  const [rows, setRows] = useState([]);
  const [detail, setDetail] = useState(null);
  const selectedRelease = useRef(null);
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);
  const [evaluation, setEvaluation] = useState('[\n  {"query":"用户真实的同义问句", "expectedIntent":"ORDER_STATUS"},\n  {"query":"和客服无关的问题", "expectedIntent":"OTHER"}\n]');
  const [resolution, setResolution] = useState('');

  const load = useCallback(async () => {
    if (section === 'workspace') return;
    try {
      setRows(await api.list(section));
      if (section === 'intent-releases' && selectedRelease.current) {
        setDetail(await api.detail('intent-releases', selectedRelease.current));
      }
      setError('');
    } catch (e) {
      setError(e.message);
    }
  }, [section]);

  useEffect(() => {
    selectedRelease.current = null;
    if (section === 'workspace') return;
    let active = true;
    api.list(section).then(data => {
      if (active) {
        setRows(data);
        setDetail(null);
      }
    }).catch(e => {
      if (active) setError(e.message);
    });
    return () => {
      active = false;
    };
  }, [section]);

  const act = async fn => {
    if (busy) return;
    setBusy(true);
    setError('');
    try {
      await fn();
      await load();
    } catch (e) {
      setError(e.message);
    } finally {
      setBusy(false);
    }
  };

  const id = row => row.handoff_no || row.run_no || row.release_no;
  const rate = value => Number.isFinite(value) ? `${(value * 100).toFixed(2)}%` : '暂无';

  return (
    <section className="customer-admin" aria-label="客服管理">
      <nav aria-label="客服管理分类" className="admin-nav-tabs">
        {[
          ['workspace', '🎧 客服接待工作台'],
          ['handoffs', '📋 转接工单历史'],
          ['runs', '🔍 运行追踪'],
          ['intent-releases', '🚀 意图库发布']
        ].map(([key, label]) => (
          <button
            key={key}
            className={`admin-nav-tab ${section === key ? 'is-active' : ''}`}
            aria-pressed={section === key}
            onClick={() => setSection(key)}
          >
            {label}
          </button>
        ))}
        {section !== 'workspace' && (
          <button disabled={busy} onClick={load} className="btn-refresh-admin">
            刷新
          </button>
        )}
      </nav>

      {section === 'workspace' ? (
        <CustomerStaffWorkspace />
      ) : (
        <>
          {error && <p className="customer-admin-error" role="alert">{error}</p>}
          {section === 'intent-releases' && (
            <details>
              <summary>构建完整意图库版本</summary>
              <p>评估集须覆盖所有启用业务意图及 OTHER 负例，且不能复制训练问句。校验 L2→L3 最终意图：L2 未命中时调用 L3，仅识别意图，不执行业务。最终结果全部正确后才可启用；详情分别统计 L2 覆盖率和最终准确率。</p>
              <label htmlFor="intent-evaluation">独立评估问句 JSON</label>
              <textarea id="intent-evaluation" value={evaluation} onChange={e => setEvaluation(e.target.value)} />
              <button disabled={busy} onClick={() => act(async () => {
                const result = await api.build(JSON.parse(evaluation));
                selectedRelease.current = result.releaseNo;
                setDetail(null);
              })}>
                构建并校验
              </button>
            </details>
          )}

          <table>
            <thead>
              <tr><th>编号</th><th>状态</th><th>创建时间</th><th>操作</th></tr>
            </thead>
            <tbody>
              {rows.map(row => (
                <tr key={id(row)}>
                  <td>{id(row)}</td>
                  <td>{row.status}</td>
                  <td>{row.created_time}</td>
                  <td>
                    <button disabled={busy} onClick={() => act(async () => {
                      if (section === 'intent-releases') selectedRelease.current = id(row);
                      setDetail(await api.detail(section, id(row)));
                    })}>详情</button>
                    {section === 'handoffs' && row.status === 'OPEN' && (
                      <button disabled={busy} onClick={() => act(() => api.process(id(row)))}>认领</button>
                    )}
                    {section === 'intent-releases' && row.status === 'READY' && (
                      <button disabled={busy} onClick={() => act(() => api.activate(id(row)))}>启用此版本</button>
                    )}
                    {section === 'intent-releases' && !['REVOKED', 'FAILED'].includes(row.status) && (
                      <button disabled={busy} onClick={() => act(() => api.revoke(id(row)))}>撤销</button>
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
          {!rows.length && <p>暂无记录。</p>}

          {detail && (
            <div>
              <h3>记录详情</h3>
              {section === 'intent-releases' && detail.report?.evaluationMode === 'L2_THEN_L3' && (
                <div>
                  <p>L2 直接命中：{detail.report.l2AcceptedCount}/{detail.report.totalCount}（{rate(detail.report.l2Coverage)}）；
                    L3 回退：{detail.report.l3FallbackCount} 条。</p>
                  <p>最终匹配：{detail.report.correctCount}/{detail.report.totalCount}（{rate(detail.report.finalAccuracy)}）；
                    异常：{detail.report.errorCount} 条。</p>
                  <p>仅校验 L2→L3 意图识别，不包含前置规则、上下文恢复或业务执行。</p>
                </div>
              )}
              <pre>{JSON.stringify(detail, null, 2)}</pre>
              {section === 'handoffs' && detail.status === 'PROCESSING' && (
                <div>
                  <label htmlFor="customer-resolution">处理结果</label>
                  <input id="customer-resolution" maxLength={512} value={resolution} onChange={e => setResolution(e.target.value)} />
                  <button disabled={busy || !resolution.trim()} onClick={() => act(async () => { await api.resolve(detail.handoff_no, resolution); setDetail(null); })}>
                    完成处理
                  </button>
                </div>
              )}
              {section === 'runs' && detail.run_no && (
                <button disabled={busy} onClick={() => act(async () => setDetail({ ...detail, toolCalls: await api.detail(`runs/${encodeURIComponent(detail.run_no)}`, 'tool-calls') }))}>
                  查看工具记录
                </button>
              )}
            </div>
          )}
        </>
      )}
    </section>
  );
};
