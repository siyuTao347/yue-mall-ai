import React from 'react';
import { ProductShowcaseCard } from './ProductShowcaseCard';
import { OrderTimelineCard } from './OrderTimelineCard';
import { RatingCard } from './RatingCard';
import { DynamicFormCard } from './DynamicFormCard';
import { HandoffStatusCard } from './HandoffStatusCard';
import { WorkflowProgressCard } from './WorkflowProgressCard';

export const RichMessageRenderer = ({ cards, onCardAction }) => {
  if (!cards || !Array.isArray(cards) || cards.length === 0) {
    return null;
  }

  const renderCard = (card, idx) => {
    const key = card.cardId || `card-${idx}`;
    switch (card.type) {
      case 'product_showcase':
        return <ProductShowcaseCard key={key} card={card} onAction={onCardAction} />;
      case 'order_timeline':
        return <OrderTimelineCard key={key} card={card} onAction={onCardAction} />;
      case 'rating':
        return <RatingCard key={key} card={card} onAction={onCardAction} />;
      case 'dynamic_form':
        return <DynamicFormCard key={key} card={card} onAction={onCardAction} />;
      case 'handoff_status':
        return <HandoffStatusCard key={key} card={card} onAction={onCardAction} />;
      case 'workflow_progress':
        return <WorkflowProgressCard key={key} card={card} onAction={onCardAction} />;
      default:
        return (
          <div key={key} className="rich-card-fallback">
            {card.fallbackText || '该结构化组件在当前版本不支持，请使用最新客户端查看。'}
          </div>
        );
    }
  };

  return (
    <div className="rich-message-cards-container">
      {cards.map((c, idx) => renderCard(c, idx))}
    </div>
  );
};
