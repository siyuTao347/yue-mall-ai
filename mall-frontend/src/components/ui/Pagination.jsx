import './Pagination.css';

export const Pagination = ({ page = 1, pageSize = 20, total = 0, hasMore = false, onPageChange }) => {
  if (total <= pageSize && page <= 1) {
    return null;
  }

  const changePage = nextPage => {
    if (nextPageInvalid(nextPage, page, hasMore)) return;
    onPageChange?.(nextPage);
  };

  return (
    <div className="pagination">
      <button type="button" className="pagination-button" disabled={page <= 1}
        onClick={() => changePage(page - 1)}>上一页</button>
      <span className="pagination-info">第 {page} 页 / 共 {Math.max(1, Math.ceil(total / pageSize))} 页 · {total} 条</span>
      <button type="button" className="pagination-button" disabled={!hasMore}
        onClick={() => changePage(page + 1)}>下一页</button>
    </div>
  );
};

const nextPageInvalid = (nextPage, currentPage, hasMore) =>
  nextPage < 1 || nextPage === currentPage || (nextPage > currentPage && !hasMore);
