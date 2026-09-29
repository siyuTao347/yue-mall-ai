import { useEffect, useRef, useState } from 'react';
import { useServerTime } from './useServerTime';

export const useCountdown = (targetTimestamp, onFinish) => {
  const { getStandardNow } = useServerTime();
  const hasFinishedRef = useRef(false);
  const onFinishRef = useRef(onFinish);

  useEffect(() => {
    onFinishRef.current = onFinish;
  }, [onFinish]);
  const [timeLeft, setTimeLeft] = useState({
    hours: '00',
    minutes: '00',
    seconds: '00',
    millis: '0',
    isFinished: false
  });

  useEffect(() => {
    if (!targetTimestamp) {
      hasFinishedRef.current = true;
      setTimeLeft({ hours: '00', minutes: '00', seconds: '00', millis: '0', isFinished: true });
      return;
    }

    hasFinishedRef.current = false;

    const tick = () => {
      const diff = Math.max(0, targetTimestamp - getStandardNow());
      if (diff <= 0) {
        setTimeLeft({ hours: '00', minutes: '00', seconds: '00', millis: '0', isFinished: true });
        if (!hasFinishedRef.current) {
          hasFinishedRef.current = true;
          onFinishRef.current?.();
        }
        return;
      }

      const totalSecs = Math.floor(diff / 1000);
      const hours = String(Math.floor(totalSecs / 3600)).padStart(2, '0');
      const minutes = String(Math.floor((totalSecs % 3600) / 60)).padStart(2, '0');
      const seconds = String(totalSecs % 60).padStart(2, '0');
      const millis = String(Math.floor((diff % 1000) / 100));

      setTimeLeft({ hours, minutes, seconds, millis, isFinished: false });
    };

    tick();
    const interval = setInterval(tick, 100);
    return () => clearInterval(interval);
  }, [targetTimestamp, getStandardNow]);

  return timeLeft;
};
