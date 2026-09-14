(() => {
  const config = globalThis.NqrbPublicConfig;
  const googleButton = document.getElementById('google-sign-in');
  const confirmation = document.getElementById('web-deletion-confirmation');
  const verificationProgress = document.getElementById('web-verification-progress');
  const confirmButton = document.getElementById('confirm-web-deletion');
  const cancelButton = document.getElementById('cancel-web-deletion');
  const progress = document.getElementById('web-deletion-progress');
  const error = document.getElementById('web-deletion-error');
  const success = document.getElementById('web-deletion-success');
  let pendingIdToken = null;
  let verificationInProgress = false;

  const clearMessages = () => {
    error.hidden = true;
    success.hidden = true;
  };

  const showGenericError = () => {
    pendingIdToken = null;
    verificationInProgress = false;
    googleButton.hidden = false;
    verificationProgress.hidden = true;
    confirmation.hidden = true;
    progress.hidden = true;
    confirmButton.disabled = false;
    cancelButton.disabled = false;
    error.hidden = false;
  };

  const postCredential = async (path, idToken) => fetch(`${config.apiBaseUrl}${path}`, {
    method: 'POST',
    mode: 'cors',
    credentials: 'omit',
    cache: 'no-store',
    referrerPolicy: 'no-referrer',
    headers: {
      'Accept': 'application/json',
      'Content-Type': 'application/json',
    },
    body: JSON.stringify({ idToken }),
  });

  const receiveGoogleCredential = async (response) => {
    if (verificationInProgress) return;
    verificationInProgress = true;
    clearMessages();
    googleButton.hidden = true;
    verificationProgress.hidden = false;
    const idToken = typeof response?.credential === 'string' ? response.credential : '';
    if (!idToken) {
      showGenericError();
      return;
    }

    try {
      const verification = await postCredential('/api/public/nqrb/account-deletion/verify', idToken);
      if (!verification.ok) {
        showGenericError();
        return;
      }

      pendingIdToken = idToken;
      verificationInProgress = false;
      verificationProgress.hidden = true;
      confirmation.hidden = false;
      confirmation.focus();
    } catch {
      showGenericError();
    }
  };

  const renderGoogleButton = () => {
    googleButton.replaceChildren();
    globalThis.google.accounts.id.renderButton(googleButton, {
      type: 'standard',
      theme: 'outline',
      size: 'large',
      shape: 'rectangular',
      text: 'signin_with',
      width: 280,
      locale: document.documentElement.lang === 'en' ? 'en' : 'ar',
    });
  };

  const initializeGoogleIdentity = () => {
    if (!config?.googleWebClientId || !config?.apiBaseUrl || !globalThis.google?.accounts?.id) {
      showGenericError();
      return;
    }

    globalThis.google.accounts.id.initialize({
      client_id: config.googleWebClientId,
      callback: receiveGoogleCredential,
      auto_select: false,
      cancel_on_tap_outside: true,
      ux_mode: 'popup',
    });
    renderGoogleButton();
  };

  cancelButton.addEventListener('click', () => {
    pendingIdToken = null;
    googleButton.hidden = false;
    confirmation.hidden = true;
    clearMessages();
  });

  confirmButton.addEventListener('click', async () => {
    if (!pendingIdToken || confirmButton.disabled) return;
    const idToken = pendingIdToken;
    pendingIdToken = null;
    clearMessages();
    confirmButton.disabled = true;
    cancelButton.disabled = true;
    progress.hidden = false;

    try {
      const deletion = await postCredential('/api/public/nqrb/account-deletion', idToken);
      confirmation.hidden = true;
      progress.hidden = true;
      confirmButton.disabled = false;
      cancelButton.disabled = false;
      if (deletion.status !== 202) {
        showGenericError();
        return;
      }
      success.hidden = false;
      googleButton.replaceChildren();
    } catch {
      showGenericError();
    }
  });

  document.addEventListener('nqrb-language-change', () => {
    if (globalThis.google?.accounts?.id && success.hidden && !googleButton.hidden) renderGoogleButton();
  });
  globalThis.addEventListener('load', initializeGoogleIdentity, { once: true });
})();
