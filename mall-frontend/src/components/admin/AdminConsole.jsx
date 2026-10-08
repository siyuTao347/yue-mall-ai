import { useState } from 'react';
import { useApp } from '../../context/AppContext';
import { Navbar } from '../layout/Navbar';
import { AdminOperations } from './AdminOperations';
import { RiskCaseWorkbench } from '../risk/RiskCaseWorkbench';
import { RagKnowledgeWorkbench } from '../agent/RagKnowledgeWorkbench';
import './AdminConsole.css';

const ADMIN_TABS = [
  { key: 'operations', label: '运营审核' },
  { key: 'risk', label: '风控案件' },
  { key: 'rag', label: '知识库管理' }
];

/** 管理员专属控制台：不再渲染用户商城、秒杀与下单链路。 */
export const AdminConsole = () => {
  const { user } = useApp();
  const [activeTab, setActiveTab] = useState('operations');

  if (user?.role !== 'ADMIN') {
    return (
      <div className="app-shell admin-shell" id="top">
        <Navbar mode="admin" activeKey={activeTab} onNavigate={setActiveTab} />
        <main className="layout-container admin-denied">
          管理控制台仅对管理员开放，请使用管理员账号登录。
        </main>
      </div>
    );
  }

  return (
    <div className="app-shell admin-shell" id="top">
      <Navbar mode="admin" activeKey={activeTab} onNavigate={setActiveTab} />
      <main className="admin-console layout-container page-section">
        <header className="admin-console-head">
          <div>
            <h2 className="admin-console-title">悦购运营管理后台</h2>
            <p className="admin-console-desc">
              商家与商品准入、售后仲裁、风控案件、RAG 知识库治理；用户侧下单与秒杀入口已隔离。
            </p>
          </div>
          <span className="admin-role-badge">ADMIN</span>
        </header>

        <nav className="admin-tabs" role="tablist" aria-label="管理后台导航">
          {ADMIN_TABS.map(tab => (
            <button key={tab.key} type="button" role="tab" aria-selected={activeTab === tab.key}
              className={`admin-tab ${activeTab === tab.key ? 'active' : ''}`}
              onClick={() => setActiveTab(tab.key)}>
              {tab.label}
            </button>
          ))}
        </nav>

        {activeTab === 'operations' ? <AdminOperations /> : null}
        {activeTab === 'risk' ? <RiskCaseWorkbench /> : null}
        {activeTab === 'rag' ? <RagKnowledgeWorkbench /> : null}
      </main>
    </div>
  );
};
