import React from 'react';
import './SessionTabs.css';

export const SessionTabs = ({ sessions = [], activeSessionId, onSelectSession }) => {
  return (
    <div className="session-timeline">
      {sessions.map(session => {
        const isActive = session.sessionId === activeSessionId;
        const statusClass = session.status === 1
          ? 'status-in-progress'
          : session.status === 0 ? 'status-upcoming' : '';

        return (
          <button
            key={session.sessionId}
            type="button"
            className={`session-tab ${isActive ? 'active' : ''} ${statusClass}`}
            onClick={() => onSelectSession(session.sessionId)}
          >
            <span className="session-time">{session.sessionName}</span>
            <span className="session-status">{session.statusText}</span>
          </button>
        );
      })}
    </div>
  );
};
