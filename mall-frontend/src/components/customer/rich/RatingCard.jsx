import React, { useState } from 'react';

export const RatingCard = ({ card, onAction }) => {
  const data = card?.data || {};
  const [rating, setRating] = useState(data.rating || 5);
  const [hoverRating, setHoverRating] = useState(0);
  const [comment, setComment] = useState(data.comment || '');
  const [submitted, setSubmitted] = useState(data.submitted || false);

  const handleSubmit = () => {
    if (submitted) return;
    setSubmitted(true);
    onAction?.({
      actionId: card?.actions?.[0]?.actionId || 'SUBMIT_RATING',
      type: 'SUBMIT_FEEDBACK',
      payload: {
        scope: data.feedbackScope || 'SESSION',
        targetNo: data.targetNo || card.cardId,
        rating,
        comment
      },
      label: '提交评价'
    });
  };

  return (
    <div className="rich-card rich-card-rating">
      <div className="rich-card-header">
        <span className="rich-card-tag">服务评价</span>
        <span className="rich-card-title">{data.title || '请为本次客服服务打分'}</span>
      </div>

      <div className="rating-stars" role="radiogroup" aria-label="评分">
        {[1, 2, 3, 4, 5].map((star) => (
          <button
            key={star}
            type="button"
            className={`star-btn ${(hoverRating || rating) >= star ? 'is-active' : ''}`}
            disabled={submitted}
            onMouseEnter={() => !submitted && setHoverRating(star)}
            onMouseLeave={() => !submitted && setHoverRating(0)}
            onClick={() => setRating(star)}
            aria-label={`${star} 星`}
          >
            ★
          </button>
        ))}
        <span className="rating-text">
          {rating === 5 ? '非常满意' : rating === 4 ? '满意' : rating === 3 ? '一般' : rating === 2 ? '不满意' : '非常不满意'}
        </span>
      </div>

      {!submitted && (
        <div className="rating-comment-box">
          <textarea
            placeholder="写下你的意见或建议（可选）..."
            value={comment}
            onChange={(e) => setComment(e.target.value)}
            maxLength={200}
            rows={2}
          />
          <button
            type="button"
            className="btn-card-action btn-primary"
            onClick={handleSubmit}
          >
            提交评价
          </button>
        </div>
      )}

      {submitted && (
        <div className="rating-submitted-notice">
          ✓ 感谢您的宝贵评价！
        </div>
      )}
    </div>
  );
};
