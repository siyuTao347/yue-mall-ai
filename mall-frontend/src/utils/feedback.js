export const showToast = (type, title, message, duration) => {
  window.dispatchEvent(new CustomEvent('app-toast', {
    detail: { type, title, message, duration }
  }));
};
