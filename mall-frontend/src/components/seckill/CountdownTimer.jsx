import React from 'react';
import { useCountdown } from '../../hooks/useCountdown';
import './CountdownTimer.css';

export const CountdownTimer = ({ session, onExpire, isLoading = false }) => {
  const targetTime = session 
    ? (session.status === 0 ? session.startTime : session.endTime)
    : null;

  const { hours, minutes, seconds, millis, isFinished } = useCountdown(
    isLoading ? null : targetTime,
    onExpire
  );

  let title = '距结束';
  if (isLoading) {
    title = '加载中';
  } else if (!session) {
    title = '暂无秒杀活动';
  } else if (session.status === 2 || isFinished) {
    title = '本场已结束';
  } else if (session.status === 0) {
    title = '距开抢';
  }

  return (
    <div className="countdown-panel">
      <span className="countdown-label">{title}</span>
      <div className="countdown-boxes">
        <span className="time-value">{hours}</span>
        <span className="time-separator">:</span>
        <span className="time-value">{minutes}</span>
        <span className="time-separator">:</span>
        <span className="time-value">{seconds}</span>
        <span className="time-separator">:</span>
        <span className="time-value millis">{millis}</span>
      </div>
    </div>
  );
};
