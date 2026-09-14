(() => {
  const root = document.documentElement;
  const arabic = document.querySelector('[data-switch="ar"]');
  const english = document.querySelector('[data-switch="en"]');
  const setLanguage = (language) => {
    const isEnglish = language === 'en';
    root.lang = isEnglish ? 'en' : 'ar';
    root.dir = isEnglish ? 'ltr' : 'rtl';
    arabic.setAttribute('aria-pressed', String(!isEnglish));
    english.setAttribute('aria-pressed', String(isEnglish));
    document.dispatchEvent(new CustomEvent('nqrb-language-change', { detail: { language } }));
  };
  arabic.addEventListener('click', () => setLanguage('ar'));
  english.addEventListener('click', () => setLanguage('en'));
})();
