import { useEffect, useEffectEvent, useRef, useState } from 'react';
import { customerServiceApi as api } from '../../api/customerServiceApi';

const newMessageId = () => globalThis.crypto?.randomUUID?.() || `c-${Date.now()}-${Math.random().toString(36).slice(2)}`;

export const CustomerServiceDialog = ({ user, position, onClose }) => {
  const [session, setSession] = useState(null);
  const [messages, setMessages] = useState([]);
  const [content, setContent] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [handoff, setHandoff] = useState(false);
  const [retry, setRetry] = useState(null);
  const controller = useRef(null);
  const input = useRef(null);
  const dialog = useRef(null);
  const end = useRef(null);
  const storageKey = `mall.customerService.session.${user?.id}`;
  const closeDialog = useEffectEvent(onClose);
  useEffect(() => {
    if (input.current && !input.current.disabled) input.current.focus();
    else dialog.current?.querySelector('button:not(:disabled)')?.focus();
    const key = e => {
      if (e.key === 'Escape') { e.preventDefault(); closeDialog(); }
      if (e.key === 'Tab') {
        const items = [...dialog.current.querySelectorAll('button:not(:disabled),textarea:not(:disabled),summary,a[href]')];
        const first = items[0], last = items.at(-1);
        if (e.shiftKey && document.activeElement === first) { e.preventDefault(); last?.focus(); }
        else if (!e.shiftKey && document.activeElement === last) { e.preventDefault(); first?.focus(); }
      }
    };
    const node = dialog.current; node.addEventListener('keydown', key);
    return () => { node.removeEventListener('keydown', key); controller.current?.abort(); };
  }, []);
  useEffect(() => {
    if (!user) return;
    const abort = new AbortController(); let saved;
    try { saved = sessionStorage.getItem(storageKey); } catch { /* optional */ }
    if (saved) {
      // Restoring a saved session is an external request; disable sending until it completes.
      // oxlint-disable-next-line react/set-state-in-effect
      setBusy(true);
      api.session(saved, abort.signal).then(data => {
        if (data.status === 'CLOSED' || data.status === 'EXPIRED') { try { sessionStorage.removeItem(storageKey); } catch { /* optional */ } return; }
        setSession(saved); setHandoff(data.status === 'HANDOFF');
        setMessages(data.messages.map(m => ({ id: m.message_no, role: m.role, content: m.content, result: m.result })));
      }).catch(e => { if (!abort.signal.aborted) setError(e.message); }).finally(() => { if (!abort.signal.aborted) setBusy(false); });
    }
    return () => abort.abort();
  }, [user, storageKey]);
  useEffect(() => { end.current?.scrollIntoView({ behavior: 'smooth', block: 'end' }); }, [messages, busy]);
  const send = async (text = content, previous = null) => {
    if (!user || busy || handoff || !text.trim()) return;
    const request = previous || { text: text.trim(), clientMessageId: newMessageId() };
    setError(''); setBusy(true); const abort = new AbortController(); controller.current = abort;
    try {
      let no = session;
      if (!no) { no = (await api.create(abort.signal)).sessionNo; setSession(no); try { sessionStorage.setItem(storageKey, no); } catch { /* optional */ } }
      if (!previous) { setMessages(m => [...m, { id: request.clientMessageId, role: 'USER', content: request.text }]); setContent(''); }
      const result = await api.send(no, request.text, request.clientMessageId, abort.signal);
      if (abort.signal.aborted) return;
      setMessages(m => [...m, { id: result.runNo, role: 'AGENT', content: result.answer, result }]);
      setHandoff(result.handoffRequired); setRetry(null);
    } catch (e) { if (!abort.signal.aborted) { setError(e.message || '发送失败'); setRetry(request); } }
    finally { if (!abort.signal.aborted) { setBusy(false); input.current?.focus(); } }
  };
  const fresh = async () => {
    if (busy) return;
    try { if (session && !handoff) await api.close(session); setSession(null); setHandoff(false); setMessages([]); setRetry(null); setError(''); try { sessionStorage.removeItem(storageKey); } catch { /* optional */ } } catch (e) { setError(e.message); }
  };
  const left = position.x > window.innerWidth / 2 ? Math.max(12, position.x - 392) : Math.min(window.innerWidth - 392, position.x + 68);
  const top = Math.max(12, Math.min(position.y, window.innerHeight - 572));
  return <section ref={dialog} id="customer-dialog" role="dialog" aria-modal="true" aria-labelledby="customer-title" className="customer-dialog" style={{ left: Math.max(12, left), top }}>
    <header><div><strong id="customer-title">平台智能客服</strong><small>{handoff ? '已转交人工处理' : '订单 · 售后 · 平台规则'}</small></div><button type="button" onClick={onClose} aria-label="关闭客服">×</button></header>
    <div className="customer-messages" role="log" aria-live="polite" aria-busy={busy}>
      {!messages.length && <p className="customer-welcome">你好！请告诉我你想查询的问题。涉及订单时，请提供订单号。</p>}
      {messages.map(m => <article key={m.id} className={`customer-message ${m.role === 'USER' ? 'customer-user' : ''}`}>
        <p>{m.content}</p>
        {m.result?.citations?.map(c => <details key={`${c.intentInstanceId}-${c.sourceNo}`}><summary>{c.title} · v{c.docVersion} · {c.titlePath}</summary><p>{c.content}</p></details>)}
        {m.result?.intentResults?.flatMap(i => (i.choices || []).map(c => <button disabled={busy || handoff} key={`${i.intentInstanceId}-${c.orderNo}`} onClick={() => send(c.orderNo)}>{c.orderNo} · {c.orderStatus}</button>))}
        {m.result?.intentResults?.map(i => i.timeline?.length ? <details key={i.intentInstanceId}><summary>订单状态记录</summary>{i.timeline.map((t, index) => <p key={index}>{t.createdTime} · {t.fromStatus} → {t.toStatus}</p>)}</details> : null)}
        {m.result?.suggestedActions?.map(action => <button key={`${action.intentInstanceId}-${action.type}`} onClick={() => { onClose(); document.getElementById('tradeSection')?.scrollIntoView({ behavior: 'smooth' }); }}>查看交易工作台 · {action.bizNo}</button>)}
        {m.result?.handoffNo && <small>人工记录：{m.result.handoffNo}</small>}
        {m.result?.runNo && <button className="customer-feedback" onClick={async e => { const button = e.currentTarget; try { await api.feedback(m.result.runNo, 5); button.disabled = true; } catch (err) { setError(err.message); } }}>有帮助</button>}
      </article>)}
      {busy && <p role="status">正在处理，请稍候…</p>}<div ref={end} />
    </div>
    {error && <div className="customer-error" role="alert">{error}{retry && <button disabled={busy} onClick={() => send(retry.text, retry)}>重试原消息</button>}</div>}
    <div className="customer-shortcuts"><button disabled={busy || handoff || !user} onClick={() => send('转人工')}>转人工</button><button disabled={busy} onClick={fresh}>新会话</button></div>
    <form onSubmit={e => { e.preventDefault(); send(); }}><label className="customer-sr-only" htmlFor="customer-input">你的问题</label><textarea ref={input} id="customer-input" maxLength={2000} disabled={!user || busy || handoff} value={content} onChange={e => setContent(e.target.value)} placeholder={!user ? '请先在页面顶部登录' : handoff ? '已转人工，可创建新会话' : '输入你的问题…'} /><button type="submit" disabled={!user || busy || handoff || !content.trim()}>发送</button></form>
  </section>;
};
