# SentriCam Hub web experience

The React application owns first-run setup and the Local Hub dashboard. A fresh Hub opens a resumable dark-mode wizard. A configured Hub silently requests a short-lived local browser session, then opens the Hub overview, QR pairing, device monitoring, and recording archive.

Home mode renders only product choices. Office and Enterprise add provider, connection-test, HTTPS, certificate, networking, media, and advanced-storage controls.

~~~bash
npm ci
npm test
npm run build
npm run dev
~~~

The development server listens at http://localhost:4173 and proxies API and SignalR requests to http://localhost:5173.

Session credentials remain in browser sessionStorage and are never rendered. Setup drafts are saved by the Hub, not localStorage. The QR contains a one-time pairing code and LAN discovery address, not a JWT.
