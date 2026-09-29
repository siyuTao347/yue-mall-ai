import React, { useCallback, useEffect, useState } from 'react';
import './UiFeedback.css';

let toastId = 0;

export const ConfirmDialog = ({
  open,
  title = '确认操作',
  message,
  confirmText = '确定',
  cancelText = '取消',
  onConfirm,
  onCancel
}) => {
  useEffect(() => {
    if (!open) return undefined;
    const handleKey = (event) => {
      if (event.key === 'Escape') onCancel?.();
    };
    document.addEventListener('keydown', handleKey);
    return () => document.removeEventListener('keydown', handleKey);
  }, [open, onCancel]);

  if (!open) return null;

  return (
    <div className="feedback-backdrop" role="presentation">
      <div className="dialog" role="dialog" aria-modal="true" aria-label={title}>
        <div className="dialog-header">
          <span className="dialog-icon info">?</span>
          <span>{title}</span>
        </div>
        <div className="dialog-body">{message}</div>
        <div className="dialog-footer">
          <button type="button" className="btn btn-secondary" onClick={onCancel}>
            {cancelText}
          </button>
          <button type="button" className="btn btn-primary" onClick={onConfirm}>
            {confirmText}
          </button>
        </div>
      </div>
    </div>
  );
};

export const Toast = ({ toast, onClose }) => (
  <div className={`toast ${toast.type}`}>
    <span className="toast-dot" />
    <div>
      <div className="toast-title">{toast.title}</div>
      {toast.message && <div className="toast-message">{toast.message}</div>}
    </div>
    <button type="button" className="toast-close" onClick={() => onClose(toast.id)} aria-label="关闭提示">
      ×
    </button>
  </div>
);

export const ToastViewport = () => {
  const [toasts, setToasts] = useState([]);

  const closeToast = useCallback((id) => {
    setToasts(prev => prev.filter(item => item.id !== id));
  }, []);

  useEffect(() => {
    const show = (event) => {
      const { type = 'info', title, message, duration = 3200 } = event.detail;
      const id = ++toastId;
      setToasts(prev => [...prev, { id, type, title, message }]);
      if (duration > 0) {
        setTimeout(() => closeToast(id), duration);
      }
    };

    window.addEventListener('app-toast', show);
    return () => window.removeEventListener('app-toast', show);
  }, [closeToast]);

  return (
    <div className="toast-viewport" aria-live="polite">
      {toasts.map(toast => (
        <Toast key={toast.id} toast={toast} onClose={closeToast} />
      ))}
    </div>
  );
};
