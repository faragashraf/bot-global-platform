import { useMemo, useState } from 'react'
import type { ReactNode } from 'react'
import { HubApi } from '../api'
import type { HubConfigurationResult, HubDatabaseProvider, HubMode, HubSetupDraft, HubSetupState, HubStoragePolicy } from '../models'
import { Icon } from './Icon'

type WizardStep =
  | 'welcome'
  | 'mode'
  | 'hub-name'
  | 'recording-folder'
  | 'storage-policy'
  | 'database'
  | 'network'
  | 'media'
  | 'summary'
  | 'configure'
  | 'ready'

const HOME_STEPS: WizardStep[] = [
  'welcome', 'mode', 'hub-name', 'recording-folder', 'storage-policy', 'summary', 'configure', 'ready',
]
const ADVANCED_STEPS: WizardStep[] = [
  'welcome', 'mode', 'hub-name', 'recording-folder', 'storage-policy', 'database', 'network', 'media',
  'summary', 'configure', 'ready',
]

export function SetupWizard({
  initial,
  onReady,
}: {
  initial: HubSetupState
  onReady: () => void
}) {
  const api = useMemo(() => new HubApi(), [])
  const [draft, setDraft] = useState(initial.draft)
  const visibleSteps = draft.mode === 'home' ? HOME_STEPS : ADVANCED_STEPS
  const savedStep = initial.state === 'Failed' || initial.state === 'NeedsRepair'
    ? draft.currentStep === 'configure' || draft.currentStep === 'ready' ? 'summary' : draft.currentStep
    : draft.currentStep
  const restoredStep = visibleSteps.includes(savedStep as WizardStep)
    ? savedStep as WizardStep
    : 'welcome'
  const [step, setStep] = useState<WizardStep>(restoredStep)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [result, setResult] = useState<HubConfigurationResult | null>(null)
  const [connectionResult, setConnectionResult] = useState<string | null>(null)
  const [failure, setFailure] = useState(initial.failure)
  const index = visibleSteps.indexOf(step)
  const progress = step === 'ready' ? 100 : Math.max(4, (index / (visibleSteps.length - 1)) * 100)

  const update = (patch: Partial<HubSetupDraft>) => setDraft((current) => ({ ...current, ...patch }))

  const go = async (direction: 1 | -1) => {
    const next = visibleSteps[index + direction]
    if (!next) return
    const validation = direction > 0 ? validateStep(step, draft) : null
    if (validation) {
      setError(validation)
      return
    }
    setError(null)
    const updated = { ...draft, currentStep: next }
    setDraft(updated)
    setStep(next)
    if (next !== 'configure' && next !== 'ready') {
      try {
        await api.saveDraft(updated)
      } catch {
        // The current browser state still lets the user continue; the next save retries.
      }
    }
  }

  const configure = async () => {
    const validation = validateStep('summary', draft)
    if (validation) {
      setError(validation)
      return
    }
    const configuring = { ...draft, currentStep: 'configure' }
    setDraft(configuring)
    setStep('configure')
    setBusy(true)
    setError(null)
    try {
      await api.saveDraft(configuring)
      const configured = await api.configure(configuring)
      setResult(configured)
      setFailure(null)
      setStep('ready')
      setDraft((current) => ({ ...current, currentStep: 'ready' }))
    } catch (failure) {
      setError(failure instanceof Error ? failure.message : 'SentriCam could not finish setup.')
      setStep('summary')
      try {
        const latest = await api.getSetup()
        setFailure(latest.failure)
      } catch {
        // Preserve the actionable request error when setup state cannot be refreshed.
      }
    } finally {
      setBusy(false)
    }
  }

  const testConnection = async () => {
    setBusy(true)
    setConnectionResult(null)
    try {
      const tested = await api.testDatabase(draft.database)
      setConnectionResult(tested.message)
    } catch (failure) {
      setConnectionResult(failure instanceof Error ? failure.message : 'Connection test failed.')
    } finally {
      setBusy(false)
    }
  }

  return (
    <main className="setup-shell">
      <header className="setup-header">
        <div className="brand">
          <span className="brand-mark brand-mark--small"><Icon name="camera" size={20}/></span>
          <div><strong>SentriCam</strong><span>Local Hub setup</span></div>
        </div>
        <span className="setup-version">Hub {initial.version}</span>
      </header>
      <div className="setup-progress" aria-hidden="true"><span style={{ width: progress + '%' }}/></div>
      <div className="setup-stage">
        <section className="setup-card" aria-live="polite">
          {failure && (
            <div className="setup-repair" role="status">
              <span><Icon name="shield" size={20}/></span>
              <div>
                <strong>{initial.state === 'NeedsRepair' ? 'Hub setup needs repair' : 'Setup did not finish'}</strong>
                <p>{failure.message}</p>
                {failure.canRetry && <small>Your saved choices are ready to review and retry.</small>}
              </div>
            </div>
          )}
          {step === 'welcome' && (
            <WizardPage eyebrow="Welcome" title="Your home, watched over." lead="SentriCam keeps your cameras and recordings together on this Hub. Setup takes only a few minutes.">
              <div className="welcome-visual">
                <div className="welcome-orbit welcome-orbit--outer"/>
                <div className="welcome-orbit welcome-orbit--inner"/>
                <span className="brand-mark welcome-mark"><Icon name="camera" size={34}/></span>
                <span className="welcome-node welcome-node--one"/><span className="welcome-node welcome-node--two"/><span className="welcome-node welcome-node--three"/>
              </div>
              <div className="trust-row"><span><Icon name="shield" size={17}/>Private by design</span><span><Icon name="wifi" size={17}/>Works on your network</span></div>
            </WizardPage>
          )}

          {step === 'mode' && (
            <WizardPage eyebrow="Choose your setup" title="Where will this Hub live?" lead="We’ll tailor the next steps. Home is the easiest and recommended choice.">
              <div className="choice-grid choice-grid--three">
                <ChoiceCard selected={draft.mode === 'home'} icon="home" title="Home" tag="Recommended" onClick={() => update({
                  mode: 'home',
                  database: {
                    ...draft.database,
                    provider: 'sqlite',
                    host: null,
                    port: null,
                    databaseName: initial.draft.database.provider === 'sqlite'
                      ? initial.draft.database.databaseName
                      : null,
                    username: null,
                    password: null,
                    hasStoredPassword: false,
                  },
                  network: { ...draft.network, httpsEnabled: false },
                  media: { ...draft.media, preferBundledEngine: true },
                })}>Automatic, private, and ready for everyday use.</ChoiceCard>
                <ChoiceCard selected={draft.mode === 'office'} icon="devices" title="Office" onClick={() => update({ mode: 'office' })}>Flexible storage and network controls for a small team.</ChoiceCard>
                <ChoiceCard selected={draft.mode === 'enterprise'} icon="shield" title="Enterprise" onClick={() => update({ mode: 'enterprise' })}>External data services, certificates, and managed networking.</ChoiceCard>
              </div>
            </WizardPage>
          )}

          {step === 'hub-name' && (
            <WizardPage eyebrow="Make it yours" title="Name your Hub" lead="This friendly name appears on your dashboard and when a phone pairs.">
              <Field label="Hub name" hint="For example: Smith Home or Front Office">
                <input autoFocus maxLength={80} value={draft.hubName} onChange={(event) => update({ hubName: event.target.value })} placeholder="My SentriCam Hub"/>
              </Field>
              <div className="name-preview"><span className="status-light"/><div><small>Preview</small><strong>{draft.hubName.trim() || 'My SentriCam Hub'}</strong></div><span>Hub Ready</span></div>
            </WizardPage>
          )}

          {step === 'recording-folder' && (
            <WizardPage eyebrow="Recording library" title="Choose where recordings live" lead="Pick a folder with enough space. SentriCam organizes everything inside it automatically.">
              <Field label="Recording folder" hint="You can move this later from Hub settings.">
                <div className="input-with-icon"><Icon name="folder" size={19}/><input autoFocus value={draft.recordingFolder} onChange={(event) => update({ recordingFolder: event.target.value })}/></div>
              </Field>
              <button className="button button--quiet" type="button" onClick={() => update({ recordingFolder: initial.defaultRecordingFolder })}>Use recommended folder</button>
              <div className="info-panel"><Icon name="shield" size={19}/><div><strong>Recordings stay with you</strong><p>Files remain in this folder on your Hub unless you choose another destination later.</p></div></div>
            </WizardPage>
          )}

          {step === 'storage-policy' && (
            <WizardPage eyebrow="Storage policy" title="How should SentriCam use space?" lead="Choose a simple policy now. The Hub watches available space for you.">
              <div className="choice-grid">
                <ChoiceCard selected={draft.storagePolicy === 'balanced'} icon="storage" title="Balanced" tag="Recommended" onClick={() => setStoragePolicy('balanced', update)}>Keep about 30 days while protecting free space.</ChoiceCard>
                <ChoiceCard selected={draft.storagePolicy === 'keep-more'} icon="recording" title="Keep more" onClick={() => setStoragePolicy('keep-more', update)}>Keep recordings longer when storage allows.</ChoiceCard>
                <ChoiceCard selected={draft.storagePolicy === 'space-saver'} icon="shield" title="Space saver" onClick={() => setStoragePolicy('space-saver', update)}>Use less disk space and retain recent moments.</ChoiceCard>
                {draft.mode !== 'home' && <ChoiceCard selected={draft.storagePolicy === 'custom'} icon="storage" title="Advanced storage" onClick={() => update({ storagePolicy: 'custom' })}>Set retention and reserved free space directly.</ChoiceCard>}
              </div>
              {draft.storagePolicy === 'custom' && <div className="field-row"><Field label="Keep recordings (days)"><input type="number" min={1} max={3650} value={draft.retentionDays} onChange={(event) => update({ retentionDays: Number(event.target.value) })}/></Field><Field label="Always leave free (GB)"><input type="number" min={1} value={draft.minimumFreeSpaceGb} onChange={(event) => update({ minimumFreeSpaceGb: Number(event.target.value) })}/></Field></div>}
            </WizardPage>
          )}

          {step === 'database' && (
            <WizardPage eyebrow="Data service" title="Choose a database" lead="Use the built-in option or connect this Hub to an existing managed service.">
              <div className="choice-grid choice-grid--three">
                {([
                  ['sqlite', 'Built in', 'Ideal for a single Hub. No separate service required.'],
                  ['sqlserver', 'SQL Server', 'Connect to an existing Microsoft data service.'],
                  ['postgresql', 'PostgreSQL', 'Connect to an existing PostgreSQL service.'],
                ] as const).map(([provider, title, description]) => (
                  <ChoiceCard key={provider} selected={draft.database.provider === provider} icon="database" title={title} onClick={() => setProvider(provider, draft, update)}>{description}</ChoiceCard>
                ))}
              </div>
              {draft.database.provider !== 'sqlite' && (
                <div className="advanced-fields">
                  <div className="field-row"><Field label="Server"><input value={draft.database.host ?? ''} onChange={(event) => updateDatabase(draft, update, { host: event.target.value })} placeholder="database.example.com"/></Field><Field label="Port"><input type="number" value={draft.database.port ?? ''} onChange={(event) => updateDatabase(draft, update, { port: event.target.value ? Number(event.target.value) : null })}/></Field></div>
                  <Field label="Database name"><input value={draft.database.databaseName ?? ''} onChange={(event) => updateDatabase(draft, update, { databaseName: event.target.value })}/></Field>
                  <div className="field-row"><Field label="Username"><input value={draft.database.username ?? ''} onChange={(event) => updateDatabase(draft, update, { username: event.target.value })}/></Field><Field label="Password" hint={draft.database.hasStoredPassword ? 'Leave blank to keep the saved password.' : undefined}><input type="password" value={draft.database.password ?? ''} onChange={(event) => updateDatabase(draft, update, { password: event.target.value })}/></Field></div>
                  <Toggle checked={draft.database.encrypt} onChange={(encrypt) => updateDatabase(draft, update, { encrypt })} title="Encrypt the connection" detail="Recommended whenever the database is on another computer."/>
                  <button className="button button--quiet" type="button" disabled={busy} onClick={testConnection}>{busy ? 'Testing…' : 'Test connection'}</button>
                  {connectionResult && <p className="inline-result" role="status">{connectionResult}</p>}
                </div>
              )}
            </WizardPage>
          )}

          {step === 'network' && (
            <WizardPage eyebrow="Network & certificates" title="Secure access on your network" lead="The default works on a trusted local network. Managed environments can enable HTTPS.">
              <Toggle checked={draft.network.httpsEnabled} onChange={(httpsEnabled) => update({ network: { ...draft.network, httpsEnabled } })} title="Use HTTPS" detail="Requires a certificate and takes effect after the Hub restarts."/>
              {draft.network.httpsEnabled && (
                <div className="advanced-fields">
                  <Toggle checked={draft.network.generateCertificate} onChange={(generateCertificate) => update({ network: { ...draft.network, generateCertificate } })} title="Create a Hub certificate automatically" detail="SentriCam creates and protects the certificate for this Hub."/>
                  {!draft.network.generateCertificate && <Field label="Certificate file"><input value={draft.network.certificatePath ?? ''} onChange={(event) => update({ network: { ...draft.network, certificatePath: event.target.value } })} placeholder="/path/to/hub-certificate.pfx"/></Field>}
                  <Field label="Network port" hint="Only change this if your network administrator asks you to."><input type="number" min={1024} max={65535} value={draft.network.port} onChange={(event) => update({ network: { ...draft.network, port: Number(event.target.value) } })}/></Field>
                </div>
              )}
            </WizardPage>
          )}

          {step === 'media' && (
            <WizardPage eyebrow="Media engine" title="Prepare video support" lead="SentriCam uses a media engine for thumbnails and recording checks. Hub packages manage it automatically.">
              <div className="capability-hero"><span><Icon name="media" size={29}/></span><div><strong>Hub-managed media</strong><p>No separate download or command-line setup is needed.</p></div><em>Automatic</em></div>
              <Toggle checked={draft.media.preferBundledEngine} onChange={(preferBundledEngine) => update({ media: { ...draft.media, preferBundledEngine } })} title="Use the Hub-managed engine" detail="Recommended. The future installer will include the correct build for this computer."/>
              {!draft.media.preferBundledEngine && <Field label="Custom FFmpeg executable"><input value={draft.media.customPath ?? ''} onChange={(event) => update({ media: { ...draft.media, customPath: event.target.value } })}/></Field>}
            </WizardPage>
          )}

          {step === 'summary' && (
            <WizardPage eyebrow="Ready to configure" title="Everything looks good" lead="SentriCam will create and protect everything it needs. Nothing technical to copy or configure.">
              <div className="summary-list">
                <SummaryRow icon="home" label="Hub" value={draft.hubName}/>
                <SummaryRow icon="folder" label="Recordings" value={draft.recordingFolder}/>
                <SummaryRow icon="storage" label="Storage" value={storageLabel(draft.storagePolicy)}/>
                {draft.mode !== 'home' && <SummaryRow icon="database" label="Data service" value={providerLabel(draft.database.provider)}/>}
                {draft.mode !== 'home' && <SummaryRow icon="wifi" label="Network" value={draft.network.httpsEnabled ? 'HTTPS enabled' : 'Local network'}/>}
              </div>
              <div className="preference-list">
                <Toggle checked={draft.startAutomatically} onChange={(startAutomatically) => update({ startAutomatically })} title="Start SentriCam automatically" detail="Keep your Hub ready after this computer starts."/>
                <Toggle checked={draft.openDashboard} onChange={(openDashboard) => update({ openDashboard })} title="Open the dashboard when ready" detail="Go straight to pairing your first device."/>
              </div>
            </WizardPage>
          )}

          {step === 'configure' && (
            <WizardPage eyebrow="Configuring your Hub" title="Making everything ready…" lead="This usually takes less than a minute. Keep this window open.">
              <div className="configure-visual"><div className="spinner spinner--large"/><span className="brand-mark"><Icon name="camera" size={26}/></span></div>
              <div className="configure-lines"><span className="active">Preparing private storage</span><span>Protecting this Hub</span><span>Starting local services</span></div>
            </WizardPage>
          )}

          {step === 'ready' && (
            <WizardPage eyebrow="Setup complete" title={(result?.hubName ?? draft.hubName) + ' is ready'} lead="Your Hub is running. Next, pair a phone and SentriCam will take it from there.">
              <div className="ready-check"><Icon name="check" size={48}/></div>
              <div className="completed-actions">{result?.completedActions.slice(0, 5).map((action) => <span key={action}><Icon name="check" size={15}/>{action}</span>)}</div>
              {result?.restartRequired && <div className="info-panel"><Icon name="shield" size={19}/><div><strong>HTTPS is prepared</strong><p>Restart the Hub after opening the dashboard to activate the new network certificate.</p></div></div>}
            </WizardPage>
          )}

          {error && <div className="error-banner setup-error" role="alert">{error}</div>}
          <footer className="setup-actions">
            {index > 0 && step !== 'configure' && step !== 'ready' && <button className="text-button" type="button" onClick={() => void go(-1)}>Back</button>}
            <span/>
            {step === 'welcome' && <button className="button button--primary" type="button" onClick={() => void go(1)}>Get started <Icon name="chevron" size={17}/></button>}
            {step !== 'welcome' && step !== 'summary' && step !== 'configure' && step !== 'ready' && <button className="button button--primary" type="button" onClick={() => void go(1)}>Continue <Icon name="chevron" size={17}/></button>}
            {step === 'summary' && <button className="button button--primary" type="button" disabled={busy} onClick={() => void configure()}>{busy ? 'Configuring…' : 'Configure Hub'} <Icon name="check" size={17}/></button>}
            {step === 'ready' && <button className="button button--primary" type="button" onClick={onReady}>Open dashboard <Icon name="chevron" size={17}/></button>}
          </footer>
        </section>
        <aside className="setup-aside">
          <span className="eyebrow">Private. Local. Simple.</span>
          <h2>The technical work stays behind the product.</h2>
          <ul><li><Icon name="check" size={16}/>Security prepared automatically</li><li><Icon name="check" size={16}/>Updates applied for you</li><li><Icon name="check" size={16}/>No accounts or cloud required</li></ul>
          <div className="setup-aside__footer"><span className="status-light"/>Progress is saved as you go</div>
        </aside>
      </div>
    </main>
  )
}

function WizardPage({ eyebrow, title, lead, children }: { eyebrow: string; title: string; lead: string; children: ReactNode }) {
  return <div className="wizard-page"><span className="eyebrow">{eyebrow}</span><h1>{title}</h1><p className="wizard-lead">{lead}</p>{children}</div>
}

function ChoiceCard({ selected, icon, title, tag, children, onClick }: { selected: boolean; icon: Parameters<typeof Icon>[0]['name']; title: string; tag?: string; children: ReactNode; onClick: () => void }) {
  return <button type="button" className={'choice-card' + (selected ? ' choice-card--selected' : '')} aria-pressed={selected} onClick={onClick}><span className="choice-card__icon"><Icon name={icon} size={22}/></span>{tag && <em>{tag}</em>}<strong>{title}</strong><p>{children}</p><span className="choice-radio">{selected && <span/>}</span></button>
}

function Field({ label, hint, children }: { label: string; hint?: string; children: ReactNode }) {
  return <label className="setup-field"><span>{label}</span>{children}{hint && <small>{hint}</small>}</label>
}

function Toggle({ checked, onChange, title, detail }: { checked: boolean; onChange: (checked: boolean) => void; title: string; detail: string }) {
  return <label className="toggle-row"><div><strong>{title}</strong><p>{detail}</p></div><input type="checkbox" checked={checked} onChange={(event) => onChange(event.target.checked)}/><span className="toggle-control" aria-hidden="true"><i/></span></label>
}

function SummaryRow({ icon, label, value }: { icon: Parameters<typeof Icon>[0]['name']; label: string; value: string }) {
  return <div className="summary-row"><span><Icon name={icon} size={19}/></span><div><small>{label}</small><strong>{value}</strong></div><Icon name="check" size={18}/></div>
}

function validateStep(step: WizardStep, draft: HubSetupDraft) {
  if (step === 'hub-name' && !draft.hubName.trim()) return 'Give this Hub a friendly name.'
  if (step === 'recording-folder' && !draft.recordingFolder.trim()) return 'Choose a recording folder.'
  if (step === 'database' && draft.database.provider !== 'sqlite' && (!draft.database.host?.trim() || !draft.database.databaseName?.trim())) return 'Enter the database server and database name.'
  if (step === 'network' && draft.network.httpsEnabled && !draft.network.generateCertificate && !draft.network.certificatePath?.trim()) return 'Choose a certificate file or let SentriCam create one.'
  if (step === 'media' && !draft.media.preferBundledEngine && !draft.media.customPath?.trim()) return 'Choose a media engine executable.'
  return null
}

function setStoragePolicy(policy: HubStoragePolicy, update: (patch: Partial<HubSetupDraft>) => void) {
  const defaults = policy === 'keep-more' ? [90, 20] : policy === 'space-saver' ? [7, 15] : [30, 10]
  update({ storagePolicy: policy, retentionDays: defaults[0], minimumFreeSpaceGb: defaults[1] })
}

function setProvider(provider: HubDatabaseProvider, draft: HubSetupDraft, update: (patch: Partial<HubSetupDraft>) => void) {
  const defaultPort = provider === 'sqlserver' ? 1433 : provider === 'postgresql' ? 5432 : null
  const databaseName = provider === 'sqlite'
    ? null
    : draft.database.provider === 'sqlite'
      ? 'sentricam'
      : draft.database.databaseName
  update({ database: { ...draft.database, provider, port: defaultPort, databaseName } })
}

function updateDatabase(draft: HubSetupDraft, update: (patch: Partial<HubSetupDraft>) => void, database: Partial<HubSetupDraft['database']>) {
  update({ database: { ...draft.database, ...database } })
}

function storageLabel(policy: HubStoragePolicy) {
  return policy === 'keep-more' ? 'Keep more' : policy === 'space-saver' ? 'Space saver' : policy === 'custom' ? 'Advanced' : 'Balanced'
}

function providerLabel(provider: HubDatabaseProvider) {
  return provider === 'sqlserver' ? 'SQL Server' : provider === 'postgresql' ? 'PostgreSQL' : 'Built in'
}
