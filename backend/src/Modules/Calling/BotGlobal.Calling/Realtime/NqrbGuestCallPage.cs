namespace BotGlobal.Calling.Realtime;

internal static class NqrbGuestCallPage
{
    private static readonly string BotGlobalMark = LoadBotGlobalMark();
    private static readonly string HtmlContent = BuildHtml();

    public static string Html() => HtmlContent;

    private static string BuildHtml()
    {
        var html = """
<!doctype html>
<html lang="ar" dir="rtl">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover">
  <meta name="theme-color" content="#211b36">
  <title>Nqrb · مكالمة ضيف</title>
  <style>
    :root {
      color-scheme: dark;
      --night: #211b36; --deep: #171226; --surface: #302740; --line: #6b5a78;
      --paper: #fff9fb; --quiet: #d4c6de; --rose: #f3bca9; --mint: #9ddac6;
      --alert: #ffc8c4; --radius: 16px;
    }
    * { box-sizing: border-box; }
    html { min-height: 100%; background: var(--deep); scrollbar-color: #796b88 var(--deep); }
    ::selection { color: var(--night); background: var(--rose); }
    body { margin: 0; min-height: 100dvh; color: var(--paper); font: 16px/1.5 system-ui, -apple-system, "Segoe UI", Tahoma, Arial, sans-serif; background: radial-gradient(ellipse 62% 48% at 12% 4%, #6c45684d, transparent 75%), radial-gradient(ellipse 55% 38% at 95% 90%, #875c7250, transparent 74%), var(--night); }
    button, input { font: inherit; }
    button { cursor: pointer; }
    button:disabled { cursor: not-allowed; opacity: .65; }
    :focus-visible { outline: 3px solid var(--mint); outline-offset: 4px; }
    .page { max-width: 1220px; min-height: 100dvh; margin: auto; padding: max(24px, env(safe-area-inset-top)) max(20px, env(safe-area-inset-right)) max(28px, env(safe-area-inset-bottom)) max(20px, env(safe-area-inset-left)); display: flex; flex-direction: column; gap: 24px; }
    .topbar { display: flex; align-items: center; justify-content: space-between; gap: 16px; }
    .brand { display: inline-flex; align-items: center; gap: 12px; font-size: 22px; font-weight: 850; letter-spacing: -.02em; direction: ltr; }
    .brand-symbol { width: 43px; aspect-ratio: 1; display: grid; place-items: center; border: 1.5px solid var(--rose); border-radius: 14px; color: var(--rose); background: #f3bca914; font-size: 25px; line-height: 1; }
    .language { border: 1px solid #ffffff48; border-radius: 100px; padding: 9px 16px; background: #ffffff0b; color: var(--paper); font-weight: 650; }
    .language:hover { background: #ffffff20; }
    .layout { flex: 1; display: grid; grid-template-columns: minmax(0, 1.04fr) minmax(370px, .96fr); align-items: center; gap: clamp(26px, 6vw, 80px); }
    .story { display: grid; gap: 26px; max-width: 610px; padding-block: 20px; }
    .headline { margin: 0; max-width: 12ch; font-size: clamp(3.25rem, 6.8vw, 6rem); line-height: 1.1; letter-spacing: -.03em; font-weight: 850; }
    .headline em { color: var(--rose); font-style: normal; }
    .story-copy { max-width: 49ch; margin: 0; color: var(--quiet); font-size: clamp(1.04rem, 1.7vw, 1.24rem); line-height: 1.75; }
    .orbit-scene { position: relative; min-height: clamp(190px, 27vw, 330px); overflow: hidden; display: grid; place-items: center; isolation: isolate; }
    .orbit { position: absolute; width: min(66%, 290px); aspect-ratio: 1; border: 1px solid #f3bca954; border-radius: 50%; transform: rotate(-24deg) scaleX(1.5); }
    .orbit.o2 { width: min(48%, 214px); border-color: #9ddac684; transform: rotate(27deg) scaleX(1.42); }
    .orbit.o3 { width: min(84%, 390px); border-color: #d4c6de25; transform: rotate(10deg) scaleX(1.3); }
    .orbit::before { content: ""; position: absolute; inset-inline-start: 50%; top: -5px; width: 9px; height: 9px; border-radius: 50%; background: var(--rose); }
    .orbit.o2::before { background: var(--mint); }
    .voice { z-index: 1; width: clamp(105px, 15vw, 148px); aspect-ratio: 1; border-radius: 32%; display: grid; place-items: center; border: 1px solid #ffffff4b; }
    .voice.one { background: linear-gradient(145deg, #f3bca9, #b8799e); transform: translate(34%, 10%); }
    .voice.two { background: linear-gradient(145deg, #b6e5d5, #698caa); transform: translate(-32%, -22%) scale(.83); }
    .voice svg { width: 46%; height: 46%; fill: none; stroke: #34263b; stroke-width: 2.1; stroke-linecap: round; stroke-linejoin: round; }
    .voice.two svg { stroke: #243445; }
    .panel { position: relative; overflow: hidden; border: 1px solid #ffffff34; border-radius: var(--radius); padding: 32px; background: linear-gradient(147deg, #40334f 0%, #2d2440 58%, #251e39 100%); }
    .panel-body { position: relative; display: grid; gap: 22px; }
    .panel h2 { margin: 0; font-size: clamp(1.75rem, 3vw, 2.25rem); line-height: 1.25; letter-spacing: -.035em; }
    .panel p { margin: 0; color: var(--quiet); line-height: 1.65; }
    .host { padding: 16px 18px; border: 1px solid #ffffff24; border-radius: 18px; background: #ffffff0a; color: var(--paper) !important; font-weight: 600; }
    label.name-field { display: grid; gap: 9px; color: var(--paper); font-weight: 700; }
    input[type="text"] { width: 100%; min-height: 56px; padding: 12px 16px; border: 1px solid #ffffff58; border-radius: 16px; color: var(--paper); caret-color: var(--rose); background: #1b1729a6; }
    input[type="text"]::placeholder { color: #d4c6de9c; }
    .consent { display: flex; align-items: flex-start; gap: 12px; color: var(--quiet); line-height: 1.5; cursor: pointer; }
    .consent input { width: 21px; height: 21px; margin: 2px 0 0; flex: none; accent-color: var(--rose); }
    .primary, .leave { width: 100%; min-height: 58px; border-radius: 18px; font-weight: 850; transition: transform .2s ease, background .2s ease; }
    .primary { border: 0; background: var(--rose); color: #281e34; }
    .primary:hover:not(:disabled) { background: #ffd0be; transform: translateY(-2px); }
    .leave { border: 1px solid #ffffff6a; background: transparent; color: var(--paper); }
    .leave:hover:not(:disabled) { background: #ffffff14; }
    .status { min-height: 24px; font-size: 14px; }
    .error { color: var(--alert) !important; }
    .call, .ended, .unavailable { display: none; text-align: center; justify-items: center; }
    [data-active="call"] .form, [data-active="ended"] .form, [data-active="unavailable"] .form { display: none; }
    [data-active="call"] .call, [data-active="ended"] .ended, [data-active="unavailable"] .unavailable { display: grid; }
    .mute { width: 100%; min-height: 48px; border: 1px solid #ffffff6a; border-radius: 16px; background: #ffffff0b; color: var(--paper); font-weight: 750; }
    .mute[aria-pressed="true"] { border-color: var(--mint); color: var(--mint); }
    .call-art { position: relative; width: 166px; height: 166px; display: grid; place-items: center; margin: 0 auto; }
    .call-art::before, .call-art::after { content: ""; position: absolute; inset: 14px; border: 1px solid #f3bca984; border-radius: 50%; animation: breathe 3s ease-in-out infinite; }
    .call-art::after { inset: 0; border-color: #9ddac67c; animation-delay: -1.5s; }
    .call-core { z-index: 1; width: 86px; height: 86px; display: grid; place-items: center; border-radius: 20px; background: linear-gradient(145deg, var(--rose), #b782ad); color: var(--night); font-size: 34px; font-weight: 800; }
    @keyframes breathe { 50% { transform: scale(1.08); opacity: .55; } }
    .discover { display: flex; align-items: center; justify-content: space-between; gap: 22px; border: 1px solid #ffffff2f; border-radius: 16px; padding: 18px 24px; background: #ffffff0a; }
    .discover-copy { display: grid; gap: 4px; }
    .discover strong { font-size: 16px; }
    .discover-copy span { color: var(--quiet); font-size: 13px; }
    .discover a { display: inline-flex; align-items: center; justify-content: center; min-height: 44px; padding: 8px 16px; border: 1px solid var(--rose); border-radius: 13px; color: var(--rose); text-decoration: none; font-weight: 800; white-space: nowrap; }
    .discover a:hover { background: #f3bca919; }
    .foot { color: #d4c6dea8; font-size: 12px; text-align: center; }
    @media (max-width: 840px) {
      .layout { grid-template-columns: 1fr; gap: 12px; }
      .story { gap: 10px; padding-block: 10px 0; }
      .headline { max-width: 16ch; font-size: clamp(2.75rem, 9vw, 4.4rem); }
      .story-copy { font-size: 1rem; }
      .orbit-scene { min-height: 160px; }
      .voice { width: 100px; }
    }
    @media (max-width: 520px) {
      .page { gap: 19px; }
      .story { gap: 12px; }
      .orbit-scene { display: none; }
      .panel { border-radius: 16px; padding: 24px; }
      .discover { align-items: stretch; flex-direction: column; gap: 14px; }
      .discover a { width: 100%; }
    }
    @media (prefers-reduced-motion: reduce) { .call-art::before, .call-art::after { animation: none; } .primary, .leave { transition: none; } }

    /* One-screen invitation: the call action stays in view on desktop in both languages. */
    .brand-lockup { display: inline-flex; align-items: center; gap: 14px; min-width: 0; }
    .brand { gap: 9px; font-size: 20px; }
    .app-mark { width: 40px; height: 40px; flex: none; border-radius: 10px; }
    .brand-divider { width: 1px; height: 28px; background: #ffffff54; }
    .platform-brand { display: inline-flex; align-items: center; gap: 8px; direction: ltr; font-size: 14px; font-weight: 760; white-space: nowrap; }
    .platform-brand img { width: 38px; height: 38px; object-fit: contain; border-radius: 10px; background: white; }
    .benefits { display: flex; flex-wrap: wrap; gap: 8px; }
    .benefits span { padding: 6px 11px; border: 1px solid #ffffff32; border-radius: 100px; color: var(--quiet); font-size: 13px; font-weight: 650; white-space: nowrap; }
    .store-badge { display: inline-flex; align-items: center; justify-content: center; gap: 9px; min-height: 44px; padding: 8px 15px; border: 1px solid var(--rose); border-radius: 13px; color: var(--paper); font-weight: 780; white-space: nowrap; }
    .store-badge svg { width: 21px; height: 21px; flex: none; }
    .page { max-width: 1300px; gap: 16px; }
    .layout { gap: clamp(24px, 4vw, 54px); }
    .story { gap: 14px; max-width: 610px; padding-block: 0; }
    .headline { max-width: 16ch; font-size: clamp(3rem, 4.7vw, 5rem); line-height: 1.04; letter-spacing: -.035em; text-wrap: balance; }
    .story-copy { max-width: 55ch; font-size: clamp(1rem, 1.3vw, 1.14rem); line-height: 1.55; }
    .orbit-scene { min-height: 190px; }
    .voice { width: 104px; border-radius: 29%; }
    .panel { padding: 24px; }
    .panel-body { gap: 15px; }
    .panel p { line-height: 1.45; }
    .host { padding: 11px 14px; }
    .host:empty { display: none; }
    input[type="text"] { min-height: 48px; }
    .primary, .leave { min-height: 50px; }
    .call-art { width: 124px; height: 124px; }
    .call-core { width: 72px; height: 72px; }
    .discover { padding: 12px 18px; }
    .foot { line-height: 1.3; }
    @media (min-width: 841px) and (min-height: 760px) {
      .page { height: 100dvh; min-height: 0; padding-block: 18px; }
      .layout { min-height: 0; }
    }
    @media (min-width: 841px) and (max-height: 820px) {
      .page { gap: 10px; padding-block: 12px; }
      .story { gap: 10px; }
      .headline { font-size: clamp(2.8rem, 4.1vw, 4.15rem); }
      .orbit-scene { display: none; }
      .panel { padding: 18px; }
      .panel-body { gap: 10px; }
      .discover { padding-block: 8px; }
    }
    @media (max-width: 840px) {
      .page { gap: 16px; }
      .story { gap: 13px; padding: 0; }
      .headline { font-size: clamp(2.5rem, 8vw, 3.7rem); }
      .orbit-scene { display: none; }
      .layout { gap: 18px; }
    }
    @media (max-width: 520px) {
      .page { padding: 12px 14px; gap: 9px; }
      .topbar { gap: 8px; }
      .language { min-height: 38px; padding: 5px 11px; }
      .brand-lockup { gap: 8px; }
      .brand { font-size: 17px; gap: 5px; }
      .app-mark { width: 34px; height: 34px; }
      .platform-brand { font-size: 11px; gap: 4px; }
      .platform-brand img { width: 32px; height: 32px; }
      .brand-divider { height: 22px; }
      .layout { gap: 11px; align-content: start; }
      .story { gap: 7px; }
      .headline { font-size: clamp(2.05rem, 8.2vw, 2.6rem); line-height: 1.04; }
      .story-copy { font-size: .88rem; line-height: 1.4; }
      .benefits { display: none; }
      .panel { padding: 15px; }
      .panel-body { gap: 9px; }
      .panel h2 { font-size: 1.55rem; }
      .panel p, label.name-field, .consent { font-size: .9rem; line-height: 1.35; }
      .host { padding: 8px 10px; }
      input[type="text"] { min-height: 44px; padding-block: 8px; }
      .primary, .leave { min-height: 46px; }
      .discover { gap: 7px; padding: 10px 12px; }
      .discover strong { font-size: 14px; }
      .discover-copy span { font-size: 11px; line-height: 1.35; }
      .store-badge { min-height: 40px; align-self: flex-start; padding: 7px 11px; font-size: 12px; }
      .foot { display: none; }
    }
  </style>
</head>
<body>
  <main class="page" data-active="form">
    <header class="topbar">
      <div class="brand-lockup">
        <span class="brand"><svg class="app-mark" viewBox="0 0 108 108" aria-hidden="true"><rect width="108" height="108" rx="25" fill="#07120e"/><circle cx="30" cy="54" r="9" fill="#62d8aa"/><circle cx="78" cy="54" r="9" fill="#62d8aa"/><path d="M42 54h24M46 48c4-6 12-6 16 0" fill="none" stroke="#62d8aa" stroke-width="5" stroke-linecap="round"/></svg><span>Nqrb</span></span>
        <span class="brand-divider" aria-hidden="true"></span>
        <span class="platform-brand"><img src="__BOT_GLOBAL_MARK__" alt=""><span>Bot Global</span></span>
      </div>
      <button class="language" id="language" type="button">English</button>
    </header>
    <div class="layout">
      <div class="story">
        <h1 class="headline" id="headline">مكالمة واحدة. <em>قُرب أكثر.</em></h1>
        <p class="story-copy" id="storyCopy">الدعوة دي فتحت لك باب لقاء صوتي خاص. اكتب اسمك، وابدأ الحديث من غير إنشاء حساب.</p>
        <div class="benefits" aria-label="مزايا الدعوة"><span id="benefitOne">دعوة برابط واحد</span><span id="benefitTwo">بدون حساب</span><span id="benefitThree">حديث يقرّبكم</span></div>
        <div class="orbit-scene" aria-hidden="true">
          <div class="orbit o3"></div><div class="orbit"></div><div class="orbit o2"></div>
          <div class="voice one"><svg viewBox="0 0 48 48"><path d="M12 20v8m6-14v20m6-27v34m6-27v20m6-14v8"/></svg></div>
          <div class="voice two"><svg viewBox="0 0 48 48"><path d="M12 21v6m6-13v20m6-27v34m6-27v20m6-13v6"/></svg></div>
        </div>
      </div>
      <section class="panel">
        <div class="panel-body form">
          <h2 id="panelTitle">مكالمتك جاهزة</h2>
          <p id="summary">اكتب الاسم الذي تحب أن يراه صاحب الدعوة، ثم انضم بصوتك.</p>
          <p class="host" id="host"></p>
          <label class="name-field" for="displayName"><span id="nameLabel">اسمك في المكالمة</span><input id="displayName" type="text" maxlength="80" autocomplete="name" value="ضيف"></label>
          <p id="historyNotice">سيظهر اسمك ووقت المكالمة لصاحب الدعوة في سجله.</p>
          <label class="consent"><input id="consent" type="checkbox"><span id="consentText">أسمح باستخدام الميكروفون لهذه المكالمة فقط.</span></label>
          <button class="primary" id="join" type="button">انضم للمكالمة</button>
          <p class="status" id="status" role="status" aria-live="polite"></p>
        </div>
        <div class="panel-body call" aria-live="polite">
          <div class="call-art" aria-hidden="true"><span class="call-core">N</span></div>
          <h2 id="callTitle">في انتظار صاحب الدعوة</h2>
          <p id="callStatus">سنوصلكما بمجرد أن يرد.</p>
          <button class="mute" id="mute" type="button" aria-pressed="false">كتم الميكروفون</button>
          <button class="leave" id="leave" type="button">إنهاء المكالمة</button>
        </div>
        <div class="panel-body ended" aria-live="polite">
          <h2 id="endedTitle">انتهت المكالمة</h2>
          <p id="endedStatus">نتمنى أن تكون لحظة جميلة.</p>
        </div>
        <div class="panel-body unavailable" aria-live="polite">
          <h2 id="unavailableTitle">الدعوة غير متاحة الآن</h2>
          <p id="unavailableBody">اطلب من صاحب الدعوة رابطًا جديدًا لنلتقي بصوتنا.</p>
        </div>
      </section>
    </div>
    <aside class="discover">
      <div class="discover-copy"><strong id="discoverTitle">المكالمة حلوة... والقُرب يستاهل يفضل</strong><span id="discoverNote">مع تطبيق Nqrb تقدر تحتفظ بأشخاصك وتبدأ مكالمتك القادمة بسهولة.</span></div>
      <div class="store-badge"><svg viewBox="0 0 24 24" aria-hidden="true"><path fill="#34a853" d="M3 2.6v18.8L13.1 12z"/><path fill="#4285f4" d="m3 2.6 12.3 7.2-2.2 2.2z"/><path fill="#fbbc04" d="m13.1 12 2.2 2.2L3 21.4z"/><path fill="#ea4335" d="m15.3 9.8 4.7 2.7c.7.4.7 1.1 0 1.5l-4.7 2.7-2.2-2.5z"/></svg><span id="playText">قريبًا على Google Play</span></div>
    </aside>
    <footer class="foot" id="foot">مكالمة بصوتك، في مساحتك الخاصة.</footer>
  </main>
  <script>
  const capability = (() => { try { return decodeURIComponent(location.hash.slice(1)); } catch { return ''; } })();
  const basePath = location.pathname.replace(/\/nqrb\/guest-call\/?$/, '');
  const text = {
    ar: {
      lang: 'English', dir: 'rtl', headline: 'مكالمة واحدة. <em>قُرب أكثر.</em>', story: 'الدعوة دي فتحت لك باب لقاء صوتي خاص. اكتب اسمك، وابدأ الحديث من غير إنشاء حساب.', benefitOne: 'دعوة برابط واحد', benefitTwo: 'بدون حساب', benefitThree: 'حديث يقرّبكم',
      title: 'مكالمتك جاهزة', summary: 'اكتب الاسم الذي تحب أن يراه صاحب الدعوة، ثم انضم بصوتك.', name: 'اسمك في المكالمة', historyNotice: 'سيظهر اسمك ووقت المكالمة لصاحب الدعوة في سجله.', consent: 'أسمح باستخدام الميكروفون لهذه المكالمة فقط.', join: 'انضم للمكالمة',
      host: name => `${name} يدعوك إلى مكالمة خاصة.`, connecting: 'بنوصلك بصاحب الدعوة...', waiting: 'في انتظار صاحب الدعوة', waitingBody: 'سنوصلكما بمجرد أن يرد.', mic: 'اسمح باستخدام الميكروفون للمتابعة.',
      failedTitle: 'انقطع الاتصال', failed: 'تعذر إكمال المكالمة الآن. جرّب شبكة أخرى أو اطلب دعوة جديدة.', ended: 'انتهت المكالمة', endedBody: 'نتمنى أن تكون لحظة جميلة.', rejectedTitle: 'لم يقبل صاحب الدعوة المكالمة', rejectedBody: 'يمكنك التواصل معه وطلب وقت مناسب للمكالمة.', expiredTitle: 'انتهى وقت الدعوة', expiredBody: 'اطلب من صاحب الدعوة رابطًا جديدًا للمحاولة مرة أخرى.', cancelledTitle: 'توقفت المكالمة قبل أن تبدأ', cancelledBody: 'يمكنكما المحاولة مجددًا برابط جديد.', localTitle: 'أنهيت المكالمة', localBody: 'شكرًا لمشاركتنا هذه اللحظة.', active: 'أنتم الآن معًا', activeBody: 'استمتع بالمكالمة.', mute: 'كتم الميكروفون', unmute: 'تشغيل الميكروفون', leave: 'إنهاء المكالمة', leaving: 'جارٍ إنهاء المكالمة...', invalid: 'هذه الدعوة لم تعد متاحة. اطلب من صاحبها رابطًا جديدًا.', unavailableTitle: 'الدعوة غير متاحة الآن', unavailableBody: 'اطلب من صاحب الدعوة رابطًا جديدًا لنلتقي بصوتنا.', previewFailedTitle: 'تعذر فتح الدعوة', previewFailedBody: 'تحقق من اتصال الإنترنت ثم أعد فتح الرابط.', hostUnavailableTitle: 'صاحب الدعوة مشغول الآن', hostUnavailableBody: 'جرّب التواصل معه واطلب رابطًا جديدًا في وقت مناسب.',
      discoverTitle: 'المكالمة حلوة... والقُرب يستاهل يفضل', discoverNote: 'مع تطبيق Nqrb تقدر تحتفظ بأشخاصك وتبدأ مكالمتك القادمة بسهولة.', play: 'قريبًا على Google Play', foot: 'Nqrb من Bot Global · صوت يقرّبنا.'
    },
    en: {
      lang: 'العربية', dir: 'ltr', headline: 'One call. <em>A little closer.</em>', story: 'This invitation opens a private voice conversation. Add your name and say hello—no account needed.', benefitOne: 'One simple link', benefitTwo: 'No account needed', benefitThree: 'A closer conversation',
      title: 'Your call is ready', summary: 'Choose the name your host will see, then join the conversation.', name: 'Your name on the call', historyNotice: 'Your name and call time will appear in your host’s call history.', consent: 'Allow microphone access for this call only.', join: 'Join the call',
      host: name => `${name} invited you to a private call.`, connecting: 'Connecting you to your host...', waiting: 'Waiting for your host', waitingBody: 'You will be together as soon as they answer.', mic: 'Allow microphone access to continue.',
      failedTitle: 'The connection was lost', failed: 'We could not complete this call. Try another network or ask for a new invitation.', ended: 'The call has ended', endedBody: 'We hope you enjoyed your time together.', rejectedTitle: 'Your host declined the call', rejectedBody: 'Reach out to them to find a better time to talk.', expiredTitle: 'The invitation has expired', expiredBody: 'Ask your host for a new link and try again.', cancelledTitle: 'The call stopped before it began', cancelledBody: 'You can try again with a new invitation.', localTitle: 'You ended the call', localBody: 'Thanks for sharing this moment.', active: 'You are together now', activeBody: 'Enjoy your conversation.', mute: 'Mute microphone', unmute: 'Turn on microphone', leave: 'End call', leaving: 'Ending your call...', invalid: 'This invitation is no longer available. Ask your host for a new link.', unavailableTitle: 'This invitation is unavailable', unavailableBody: 'Ask your host for a new link to meet by voice.', previewFailedTitle: 'We could not open the invitation', previewFailedBody: 'Check your connection, then reopen the link.', hostUnavailableTitle: 'Your host is busy right now', hostUnavailableBody: 'Reach out to them and ask for a new link at a better time.',
      discoverTitle: 'A good conversation deserves to continue', discoverNote: 'Keep your people close and start your next call with Nqrb.', play: 'Coming soon to Google Play', foot: 'Nqrb by Bot Global · A voice that brings us closer.'
    }
  };
  let lang = navigator.language && navigator.language.startsWith('ar') ? 'ar' : 'en';
  let localStream, peer, hub, hubPromise, callId, hostDisplayName, generation = Date.now(), offerStarted = false;
  let callPhase = 'form', leaving = false, previewInvalid = false, unavailableKind = 'unavailable', endedKind = 'ended', muted = false, disconnectTimer, endRequestPromise;
  const requestKeyName = `nqrb-guest-call-${capability}`;
  const joinedKeyName = `${requestKeyName}-joined`;
  const newRequestId = () => (globalThis.crypto && crypto.randomUUID ? crypto.randomUUID() : `${Date.now()}-${Math.random().toString(16).slice(2)}`);
  const clientRequestId = sessionStorage.getItem(requestKeyName) || newRequestId();
  sessionStorage.setItem(requestKeyName, clientRequestId);
  const $ = id => document.getElementById(id);
  function applyLang() {
    const t = text[lang]; document.documentElement.lang = lang; document.documentElement.dir = t.dir;
    $('language').textContent = t.lang; $('headline').innerHTML = t.headline;
    $('storyCopy').textContent = t.story; $('benefitOne').textContent = t.benefitOne; $('benefitTwo').textContent = t.benefitTwo; $('benefitThree').textContent = t.benefitThree; $('panelTitle').textContent = t.title;
    $('summary').textContent = t.summary; $('nameLabel').textContent = t.name; $('historyNotice').textContent = t.historyNotice; $('consentText').textContent = t.consent;
    if ($('displayName').value === 'ضيف' || $('displayName').value === 'Guest') $('displayName').value = lang === 'ar' ? 'ضيف' : 'Guest';
    $('join').textContent = t.join; $('leave').textContent = t.leave; $('mute').textContent = muted ? t.unmute : t.mute;
    $('discoverTitle').textContent = t.discoverTitle; $('discoverNote').textContent = t.discoverNote;
    $('playText').textContent = t.play; $('foot').textContent = t.foot;
    if (hostDisplayName) $('host').textContent = t.host(hostDisplayName);
    if (previewInvalid && callPhase === 'form') status(t.invalid, true);
    if (callPhase === 'unavailable') showUnavailable(unavailableKind);
    if (callPhase === 'call') {
      $('callTitle').textContent = peer && peer.connectionState === 'connected' ? t.active : t.waiting;
      $('callStatus').textContent = peer && peer.connectionState === 'connected' ? t.activeBody : t.waitingBody;
    }
    if (callPhase === 'ended') showEnded(endedKind);
  }
  function showUnavailable(kind) {
    const t = text[lang];
    $('unavailableTitle').textContent = kind === 'expired' ? t.expiredTitle : kind === 'previewFailed' ? t.previewFailedTitle : kind === 'hostUnavailable' ? t.hostUnavailableTitle : t.unavailableTitle;
    $('unavailableBody').textContent = kind === 'expired' ? t.expiredBody : kind === 'previewFailed' ? t.previewFailedBody : kind === 'hostUnavailable' ? t.hostUnavailableBody : t.unavailableBody;
  }
  function showEnded(kind) {
    const t = text[lang];
    const presentation = {
      rejected: [t.rejectedTitle, t.rejectedBody], expired: [t.expiredTitle, t.expiredBody],
      cancelled: [t.cancelledTitle, t.cancelledBody], failed: [t.failedTitle, t.failed],
      local: [t.localTitle, t.localBody], ended: [t.ended, t.endedBody]
    }[kind] || [t.ended, t.endedBody];
    $('endedTitle').textContent = presentation[0]; $('endedStatus').textContent = presentation[1];
    $('endedStatus').classList.toggle('error', kind === 'failed');
  }
  function status(message, error) { $('status').textContent = message; $('status').className = error ? 'status error' : 'status'; }
  async function api(path, options) {
    const response = await fetch(path, { ...options, headers: { 'content-type': 'application/json', ...(options && options.headers || {}) } });
    if (!response.ok) { const error = new Error(String(response.status)); error.status = response.status; try { error.code = (await response.json()).code; } catch {} throw error; }
    return response.status === 204 ? null : response.json();
  }
  async function preview() {
    try {
      if (!capability) throw new Error('missing-capability');
      const value = await api(`${basePath}/api/public/nqrb/guest-call-invites/preview`, { method: 'POST', body: JSON.stringify({ capability }) });
      hostDisplayName = value.hostDisplayName;
      previewInvalid = false;
      $('host').textContent = text[lang].host(hostDisplayName);
    } catch (error) {
      previewInvalid = true;
      if (sessionStorage.getItem(joinedKeyName) === 'true') status(text[lang].invalid, true);
      else {
        unavailableKind = error.status === 410 ? 'expired' : error.status === 404 || error.message === 'missing-capability' ? 'unavailable' : 'previewFailed';
        callPhase = 'unavailable'; document.querySelector('main').dataset.active = 'unavailable'; showUnavailable(unavailableKind);
      }
    }
  }
  function frame(value) { return JSON.stringify(value) + String.fromCharCode(30); }
  async function connectHub() {
    const negotiation = await fetch(`${basePath}/hubs/calling/negotiate?negotiateVersion=1`, { method: 'POST' });
    if (!negotiation.ok) throw new Error('negotiate-failed');
    const { connectionToken } = await negotiation.json();
    const scheme = location.protocol === 'https:' ? 'wss' : 'ws';
    const socket = new WebSocket(`${scheme}://${location.host}${basePath}/hubs/calling?id=${encodeURIComponent(connectionToken)}`);
    const pending = new Map(); let nextId = 1;
    socket.onopen = () => socket.send(frame({ protocol: 'json', version: 1 }));
    socket.onmessage = event => String(event.data).split(String.fromCharCode(30)).filter(Boolean).forEach(raw => {
      const msg = JSON.parse(raw);
      if (msg.type === 1) handleHubEvent(msg.target, msg.arguments || []).catch(() => leave('failed'));
      if (msg.type === 3 && msg.invocationId) {
        const pair = pending.get(msg.invocationId); if (!pair) return;
        pending.delete(msg.invocationId); msg.error ? pair.reject(new Error(msg.error)) : pair.resolve(msg.result);
      }
    });
    socket.onclose = () => {
      for (const pair of pending.values()) pair.reject(new Error('connection-closed'));
      pending.clear();
      if (!leaving && callPhase === 'call')
        resolveEarlyEndKind().then(kind => { if (!leaving && callPhase === 'call') finish(kind || 'failed'); });
    };
    await new Promise((resolve, reject) => { socket.addEventListener('open', resolve, { once: true }); socket.addEventListener('error', reject, { once: true }); });
    return {
      invoke(target, arg) {
        if (socket.readyState !== WebSocket.OPEN) return Promise.reject(new Error('connection-closed'));
        const invocationId = String(nextId++);
        const result = new Promise((resolve, reject) => pending.set(invocationId, { resolve, reject }));
        socket.send(frame({ type: 1, invocationId, target, arguments: [arg] }));
        return result;
      },
      stop() { if (socket.readyState < WebSocket.CLOSING) socket.close(); }
    };
  }
  async function startPeer() {
    hubPromise = connectHub();
    hub = await hubPromise;
    if (leaving) {
      try { await Promise.race([requestEnd(hub), new Promise((_, reject) => setTimeout(() => reject(new Error('leave-timeout')), 5000))]); }
      catch { /* The invitation will expire if its connection cannot be reached. */ }
      hub.stop(); return;
    }
    const ice = await hub.invoke('GetCallIceConfiguration', callId);
    peer = new RTCPeerConnection({ iceServers: ice.servers.map(s => ({ urls: s.urls, username: s.username || undefined, credential: s.credential || undefined })) });
    localStream.getTracks().forEach(track => peer.addTrack(track, localStream));
    peer.onicecandidate = e => { if (e.candidate && !leaving) hub.invoke('CallIceCandidate', { callId, generation, candidate: e.candidate.candidate, sdpMid: e.candidate.sdpMid, sdpMLineIndex: e.candidate.sdpMLineIndex || 0 }).catch(() => {}); };
    peer.ontrack = e => { let audio = document.querySelector('audio'); if (!audio) { audio = document.createElement('audio'); audio.autoplay = true; document.body.appendChild(audio); } audio.srcObject = e.streams[0]; audio.play().catch(() => {}); };
    peer.onconnectionstatechange = () => {
      if (callPhase !== 'call') return;
      if (peer.connectionState === 'connected') { clearTimeout(disconnectTimer); $('callTitle').textContent = text[lang].active; $('callStatus').textContent = text[lang].activeBody; }
      if (peer.connectionState === 'failed') leave('failed');
      if (peer.connectionState === 'disconnected') disconnectTimer = setTimeout(() => { if (peer && peer.connectionState === 'disconnected') leave('failed'); }, 8000);
    };
    const joined = await hub.invoke('JoinCall', { callId, generation });
    if (leaving) return;
    if (joined.peerPresent && joined.isInitiator) await sendOffer();
  }
  async function sendOffer() {
    if (offerStarted || leaving || !peer) return;
    offerStarted = true;
    try {
      const offer = await peer.createOffer({ offerToReceiveAudio: true });
      await peer.setLocalDescription(offer);
      await hub.invoke('CallOffer', { callId, generation, sessionDescription: offer.sdp });
    } catch (error) { offerStarted = false; throw error; }
  }
  async function handleHubEvent(target, args) {
    const value = args[0] || {};
    if (target === 'CallEnded' || target === 'CallRejected') {
      if (callPhase === 'call' && !leaving) finish(target === 'CallRejected' ? 'rejected' : endKind(value.reason));
      return;
    }
    if (callPhase !== 'call' || leaving || !peer) return;
    if (target === 'CallPeerJoined' && value.receiverGeneration === generation) await sendOffer();
    if (target === 'CallOffer') {
      await peer.setRemoteDescription({ type: 'offer', sdp: value.sessionDescription });
      const answer = await peer.createAnswer(); await peer.setLocalDescription(answer);
      await hub.invoke('CallAnswer', { callId, generation, sessionDescription: answer.sdp });
    }
    if (target === 'CallAnswer') await peer.setRemoteDescription({ type: 'answer', sdp: value.sessionDescription });
    if (target === 'CallIceCandidate' && value.candidate) await peer.addIceCandidate({ candidate: value.candidate, sdpMid: value.sdpMid, sdpMLineIndex: value.sdpMLineIndex });
  }
  function endKind(reason) {
    return ['rejected', 'expired', 'cancelled', 'failed', 'ended'].includes(reason) ? reason : 'ended';
  }
  async function join() {
    if (callPhase !== 'form') return;
    if (!$('consent').checked) return status(text[lang].mic, true);
    $('join').disabled = true; status(text[lang].connecting);
    try {
      localStream = await navigator.mediaDevices.getUserMedia({ audio: true, video: false });
      if (leaving) return;
      const joined = await api(`${basePath}/api/public/nqrb/guest-call-invites/join`, { method: 'POST', body: JSON.stringify({ capability, displayName: $('displayName').value, microphoneConsent: true, clientRequestId }) });
      sessionStorage.setItem(joinedKeyName, 'true');
      callId = joined.callId; callPhase = 'call'; document.querySelector('main').dataset.active = 'call';
      $('callTitle').textContent = text[lang].waiting; $('callStatus').textContent = text[lang].waitingBody;
      await startPeer();
    } catch (error) {
      if (callPhase === 'form') {
        const invitationUnavailable = [404, 409, 410].includes(error.status);
        if (invitationUnavailable) {
          unavailableKind = error.status === 410 ? 'expired' : error.code === 'nqrb_guest_call_host_unavailable' ? 'hostUnavailable' : 'unavailable';
          callPhase = 'unavailable'; document.querySelector('main').dataset.active = 'unavailable'; showUnavailable(unavailableKind);
        } else {
          $('join').disabled = false;
          status(text[lang].failed, true);
        }
        releaseMedia();
      }
      else if (!leaving) {
        const terminalKind = await resolveEarlyEndKind();
        if (terminalKind) finish(terminalKind);
        else await leave('failed');
      }
    }
  }
  async function resolveEarlyEndKind() {
    const controller = new AbortController();
    const timeout = setTimeout(() => controller.abort(), 4000);
    try {
      await api(`${basePath}/api/public/nqrb/guest-call-invites/preview`, {
        method: 'POST', body: JSON.stringify({ capability, clientRequestId }), signal: controller.signal
      });
    } catch (error) {
      if (error.code === 'nqrb_guest_call_rejected') return 'rejected';
      if (error.code === 'nqrb_guest_call_cancelled') return 'cancelled';
      if (error.status === 410) return 'expired';
    } finally {
      clearTimeout(timeout);
    }
    return null;
  }
  function releaseMedia() {
    clearTimeout(disconnectTimer);
    if (peer) { peer.close(); peer = null; }
    if (hub) { hub.stop(); hub = null; }
    if (localStream) { localStream.getTracks().forEach(track => track.stop()); localStream = null; }
    muted = false; $('mute').setAttribute('aria-pressed', 'false');
    const audio = document.querySelector('audio'); if (audio) { audio.srcObject = null; audio.remove(); }
  }
  function finish(kind = 'ended') {
    if (callPhase === 'ended') return;
    callPhase = 'ended'; leaving = true; endedKind = kind;
    releaseMedia(); document.querySelector('main').dataset.active = 'ended';
    showEnded(kind);
  }
  function requestEnd(connection, reason = 'local') {
    if (!endRequestPromise) endRequestPromise = connection.invoke('EndCall', { callId, reason });
    return endRequestPromise;
  }
  async function leave(kind = 'local') {
    if (callPhase !== 'call' || leaving) return;
    leaving = true; $('leave').disabled = true; $('callStatus').textContent = text[lang].leaving;
    try {
      const connectedHub = hub || (hubPromise && await Promise.race([
        hubPromise, new Promise((_, reject) => setTimeout(() => reject(new Error('connection-timeout')), 5000))
      ]));
      if (connectedHub && callId) await Promise.race([
        requestEnd(connectedHub, kind === 'failed' ? 'failed' : 'local'),
        new Promise((_, reject) => setTimeout(() => reject(new Error('leave-timeout')), 5000))
      ]);
    } catch { /* The guest can still leave when the connection is already gone. */ }
    finish(kind);
  }
  $('language').addEventListener('click', () => { lang = lang === 'en' ? 'ar' : 'en'; applyLang(); });
  $('join').addEventListener('click', join);
  $('mute').addEventListener('click', () => {
    if (callPhase !== 'call' || !localStream) return;
    muted = !muted;
    localStream.getAudioTracks().forEach(track => { track.enabled = !muted; });
    $('mute').setAttribute('aria-pressed', String(muted)); $('mute').textContent = muted ? text[lang].unmute : text[lang].mute;
  });
  $('leave').addEventListener('click', () => leave());
  applyLang(); preview();
  </script>
</body>
</html>
""";
        return html.Replace("__BOT_GLOBAL_MARK__", BotGlobalMark, StringComparison.Ordinal);
    }

    private static string LoadBotGlobalMark()
    {
        using var stream = typeof(NqrbGuestCallPage).Assembly.GetManifestResourceStream("BotGlobal.Calling.BotGlobalMark.png")
            ?? throw new InvalidOperationException("The Bot Global mark is missing from the Calling assembly.");
        using var buffer = new MemoryStream();
        stream.CopyTo(buffer);
        return "data:image/png;base64," + Convert.ToBase64String(buffer.ToArray());
    }
}
