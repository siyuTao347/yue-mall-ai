import { useCallback, useEffect, useRef, useState } from 'react';
import { useApp } from '../../context/AppContext';
import { useCustomerRealtime } from '../../context/CustomerRealtimeContext';
import { CustomerServiceDialog } from './CustomerServiceDialog';
import './CustomerService.css';

const POSITION_KEY = 'mall.customerService.launcher.position.v1';
const clampPosition = (point, width, height) => ({
  x: Math.max(12, Math.min(Number.isFinite(point?.x) ? point.x : width - 76, Math.max(12, width - 68))),
  y: Math.max(12, Math.min(Number.isFinite(point?.y) ? point.y : height - 88, Math.max(12, height - 68)))
});

const initialPosition = () => {
  let saved;
  try {
    saved = JSON.parse(localStorage.getItem(POSITION_KEY));
  } catch {
    /* use default */
  }
  if (!Number.isSafeInteger(saved?.x) || !Number.isSafeInteger(saved?.y)) saved = null;
  return clampPosition(saved, window.innerWidth, window.innerHeight);
};

export const CustomerServiceLauncher = () => {
  const { user } = useApp();
  const { isDialogOpen, openDialog, closeDialog, unreadCount, serviceMode } = useCustomerRealtime();
  const [position, setPosition] = useState(initialPosition);
  const [dragging, setDragging] = useState(false);
  const [anchor, setAnchor] = useState(position);
  const drag = useRef(null);
  const launcher = useRef(null);

  useEffect(() => {
    const resize = () => setPosition(p => {
      const next = clampPosition(p, window.innerWidth, window.innerHeight);
      setAnchor(next);
      return next;
    });
    window.addEventListener('resize', resize);
    return () => window.removeEventListener('resize', resize);
  }, []);

  const finish = event => {
    const state = drag.current;
    if (!state || state.pointerId !== event.pointerId) return;
    if (event.currentTarget.hasPointerCapture?.(event.pointerId)) {
      event.currentTarget.releasePointerCapture(event.pointerId);
    }
    drag.current = null;
    setDragging(false);
    if (state.moved) {
      const p = clampPosition(state.latest, window.innerWidth, window.innerHeight);
      setPosition(p);
      setAnchor(p);
      try {
        localStorage.setItem(POSITION_KEY, JSON.stringify({ x: Math.round(p.x), y: Math.round(p.y) }));
      } catch {
        /* storage optional */
      }
    }
  };

  const handleClose = useCallback(() => {
    closeDialog();
    launcher.current?.focus();
  }, [closeDialog]);

  const suppressClick = useRef(false);

  return (
    <>
      <button
        ref={launcher}
        type="button"
        className={`customer-launcher ${unreadCount > 0 ? 'has-unread' : ''} ${serviceMode === 'HUMAN' ? 'mode-human' : ''}`}
        style={{ left: position.x, top: position.y }}
        aria-label="打开客服中心"
        aria-grabbed={dragging}
        aria-expanded={isDialogOpen}
        aria-controls="customer-dialog"
        onPointerDown={e => {
          if (e.button !== 0 || drag.current) return;
          suppressClick.current = false;
          drag.current = { pointerId: e.pointerId, startX: e.clientX, startY: e.clientY, origin: position, latest: position, moved: false };
          e.currentTarget.setPointerCapture(e.pointerId);
        }}
        onPointerMove={e => {
          const d = drag.current;
          if (!d || d.pointerId !== e.pointerId) return;
          const dx = e.clientX - d.startX, dy = e.clientY - d.startY;
          if (Math.hypot(dx, dy) > 4) {
            d.moved = true;
            setDragging(true);
          }
          if (d.moved) {
            suppressClick.current = true;
            d.latest = clampPosition({ x: d.origin.x + dx, y: d.origin.y + dy }, window.innerWidth, window.innerHeight);
            setPosition(d.latest);
          }
        }}
        onPointerUp={finish}
        onPointerCancel={e => {
          suppressClick.current = true;
          finish(e);
        }}
        onClick={e => {
          if (e.detail === 0 || !suppressClick.current) {
            if (isDialogOpen) closeDialog();
            else openDialog();
          }
          suppressClick.current = false;
        }}
      >
        <span className="customer-launcher-icon" aria-hidden="true">
          {serviceMode === 'HUMAN' ? '👩‍💼' : '💬'}
        </span>
        <small className="customer-launcher-label">
          {serviceMode === 'HUMAN' ? '人工' : '客服'}
        </small>
        {unreadCount > 0 && (
          <span className="customer-unread-badge" aria-label={`${unreadCount} 条未读消息`}>
            {unreadCount > 99 ? '99+' : unreadCount}
          </span>
        )}
      </button>

      {isDialogOpen && (
        <CustomerServiceDialog
          key={user?.id || 'guest'}
          user={user}
          position={anchor}
          onClose={handleClose}
        />
      )}
    </>
  );
};
