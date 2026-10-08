/** 破坏性操作二次确认弹窗。 */
export const RagConfirmDialog = ({ open, title, message, details = [], confirmText = '确认', tone = 'primary', busy = false, onCancel, onConfirm }) => {
  if (!open) return null;
  return (
    <div className="rag-dialog-mask" role="presentation">
      <div className="rag-dialog" role="dialog" aria-modal="true" aria-label={title}>
        <h4 className="rag-dialog-title">{title}</h4>
        {message ? <p className="rag-dialog-message">{message}</p> : null}
        {details.length > 0 ? (
          <ul className="rag-dialog-details">
            {details.map(item => (
              <li key={item.label}>
                <span>{item.label}</span>
                <strong>{item.value}</strong>
              </li>
            ))}
          </ul>
        ) : null}
        <div className="rag-dialog-actions">
          <button type="button" className="rag-button" onClick={onCancel} disabled={busy}>取消</button>
          <button type="button" className={`rag-button ${tone}`} onClick={onConfirm} disabled={busy}>
            {busy ? '处理中…' : confirmText}
          </button>
        </div>
      </div>
    </div>
  );
};
