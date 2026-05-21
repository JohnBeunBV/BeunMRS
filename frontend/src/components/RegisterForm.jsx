import { useState } from 'react'

const PROVIDERS = ['SwiftSend', 'SecurePost', 'LegacyLink', 'AsyncFlow']

const PROVIDER_FIELDS = {
  SwiftSend:  { keyLabel: 'API Key',    keyPlaceholder: 'sk-swiftsend-...', extraLabel: null },
  SecurePost: { keyLabel: 'Client ID',  keyPlaceholder: 'securepost-client-id', extraLabel: 'Client Secret', extraPlaceholder: 'securepost-secret-key' },
  LegacyLink: { keyLabel: 'Gebruikersnaam', keyPlaceholder: 'legacylink-user', extraLabel: 'Wachtwoord', extraPlaceholder: 'legacylink-password' },
  AsyncFlow:  { keyLabel: 'API Key',    keyPlaceholder: 'asyncflow-api-key', extraLabel: null },
}

const TIMEZONES = [
  { value: 'Europe/Amsterdam',   label: 'Europe/Amsterdam (CET/CEST)' },
  { value: 'Europe/London',      label: 'Europe/London (GMT/BST)' },
  { value: 'Europe/Paris',       label: 'Europe/Paris (CET/CEST)' },
  { value: 'Europe/Berlin',      label: 'Europe/Berlin (CET/CEST)' },
  { value: 'Europe/Istanbul',    label: 'Europe/Istanbul (TRT)' },
  { value: 'Africa/Cairo',       label: 'Africa/Cairo (EET)' },
  { value: 'Africa/Nairobi',     label: 'Africa/Nairobi (EAT)' },
  { value: 'Asia/Dubai',         label: 'Asia/Dubai (GST)' },
  { value: 'Asia/Kolkata',       label: 'Asia/Kolkata (IST)' },
  { value: 'Asia/Bangkok',       label: 'Asia/Bangkok (ICT)' },
  { value: 'Asia/Singapore',     label: 'Asia/Singapore (SGT)' },
  { value: 'Asia/Shanghai',      label: 'Asia/Shanghai (CST)' },
  { value: 'Asia/Tokyo',         label: 'Asia/Tokyo (JST)' },
  { value: 'Australia/Sydney',   label: 'Australia/Sydney (AEST/AEDT)' },
  { value: 'America/New_York',   label: 'America/New_York (EST/EDT)' },
  { value: 'America/Chicago',    label: 'America/Chicago (CST/CDT)' },
  { value: 'America/Denver',     label: 'America/Denver (MST/MDT)' },
  { value: 'America/Los_Angeles',label: 'America/Los_Angeles (PST/PDT)' },
  { value: 'America/Sao_Paulo',  label: 'America/Sao_Paulo (BRT)' },
  { value: 'Pacific/Auckland',   label: 'Pacific/Auckland (NZST/NZDT)' },
]

const EMPTY_FORM = {
  slug: '',
  displayName: '',
  timezone: 'Europe/Amsterdam',
  openmrsHost: 'http://gateway/openmrs',
  openmrsUser: 'admin',
  openmrsPassword: '',
  providerName: 'SwiftSend',
  providerApiKey: '',
  providerExtra: '',
}

export default function RegisterForm() {
  const [form, setForm]       = useState(EMPTY_FORM)
  const [result, setResult]   = useState(null)
  const [error, setError]     = useState(null)
  const [loading, setLoading] = useState(false)
  const [copied, setCopied]   = useState(false)

  const handleChange = e => setForm({ ...form, [e.target.name]: e.target.value })

  const handleProviderChange = e => {
    setForm({ ...form, providerName: e.target.value, providerApiKey: '', providerExtra: '' })
  }

  const handleSubmit = async e => {
    e.preventDefault()
    setLoading(true)
    setError(null)
    setResult(null)

    try {
      const res  = await fetch('/api/register', {
        method:  'POST',
        headers: { 'Content-Type': 'application/json' },
        body:    JSON.stringify(form),
      })
      const data = await res.json()

      if (!res.ok) {
        setError(data.message || `Fout ${res.status}`)
      } else {
        setResult(data)
      }
    } catch (err) {
      setError('Kon de server niet bereiken: ' + err.message)
    } finally {
      setLoading(false)
    }
  }

  const copyKey = () => {
    navigator.clipboard.writeText(result.apiKey)
    setCopied(true)
    setTimeout(() => setCopied(false), 2000)
  }

  if (result) {
    return (
      <div className="card success-card">
        <div className="success-icon">✓</div>
        <h2>Tenant geregistreerd</h2>

        <table className="result-table">
          <tbody>
            <tr><th>Naam</th><td>{result.displayName}</td></tr>
            <tr><th>Slug</th><td>{result.slug}</td></tr>
            <tr><th>Tenant ID</th><td><code>{result.tenantId}</code></td></tr>
          </tbody>
        </table>

        <div className="api-key-box">
          <label>API Key — sla dit op, wordt niet opnieuw getoond</label>
          <div className="api-key-row">
            <code className="api-key-value">{result.apiKey}</code>
            <button type="button" className="btn-copy" onClick={copyKey}>
              {copied ? '✓ Gekopieerd' : 'Kopiëren'}
            </button>
          </div>
        </div>

        <p className="api-key-hint">
          Stuur deze key mee als <code>X-API-Key</code> header bij elk verzoek aan de notificatieservice.
        </p>

        <button className="btn-secondary" onClick={() => { setResult(null); setForm(EMPTY_FORM) }}>
          Nog een tenant registreren
        </button>
      </div>
    )
  }

  const providerCfg = PROVIDER_FIELDS[form.providerName]

  return (
    <form className="card" onSubmit={handleSubmit}>
      <h2>Nieuwe tenant registreren</h2>
      <p className="form-intro">Registreer een ziekenhuis als nieuwe tenant. Elke tenant heeft een eigen OpenMRS-instantie en messaging provider.</p>

      <fieldset>
        <legend>Organisatie</legend>
        <div className="field">
          <label htmlFor="slug">Slug <span className="hint">alleen kleine letters, cijfers en koppeltekens</span></label>
          <input id="slug" name="slug" value={form.slug} onChange={handleChange}
            pattern="[a-z0-9\-]+" placeholder="amsterdam-umc" required />
        </div>
        <div className="field">
          <label htmlFor="displayName">Naam</label>
          <input id="displayName" name="displayName" value={form.displayName}
            onChange={handleChange} placeholder="Amsterdam UMC" required />
        </div>
        <div className="field">
          <label htmlFor="timezone">Tijdzone <span className="hint">bepaalt weergave tijden in patiëntberichten</span></label>
          <select id="timezone" name="timezone" value={form.timezone} onChange={handleChange}>
            {TIMEZONES.map(tz => (
              <option key={tz.value} value={tz.value}>{tz.label}</option>
            ))}
          </select>
        </div>
      </fieldset>

      <fieldset>
        <legend>OpenMRS verbinding</legend>
        <div className="field">
          <label htmlFor="openmrsHost">Host URL</label>
          <input id="openmrsHost" name="openmrsHost" value={form.openmrsHost}
            onChange={handleChange} placeholder="http://gateway/openmrs" required />
        </div>
        <div className="field-row">
          <div className="field">
            <label htmlFor="openmrsUser">Gebruikersnaam</label>
            <input id="openmrsUser" name="openmrsUser" value={form.openmrsUser}
              onChange={handleChange} required />
          </div>
          <div className="field">
            <label htmlFor="openmrsPassword">Wachtwoord</label>
            <input id="openmrsPassword" type="password" name="openmrsPassword"
              value={form.openmrsPassword} onChange={handleChange} required />
          </div>
        </div>
      </fieldset>

      <fieldset>
        <legend>Messaging provider</legend>
        <div className="field">
          <label htmlFor="providerName">Provider</label>
          <select id="providerName" name="providerName" value={form.providerName}
            onChange={handleProviderChange}>
            {PROVIDERS.map(p => <option key={p} value={p}>{p}</option>)}
          </select>
        </div>

        <div className={providerCfg.extraLabel ? 'field-row' : 'field'}>
          <div className="field">
            <label htmlFor="providerApiKey">{providerCfg.keyLabel}</label>
            <input id="providerApiKey" name="providerApiKey" value={form.providerApiKey}
              onChange={handleChange} placeholder={providerCfg.keyPlaceholder} required />
          </div>

          {providerCfg.extraLabel && (
            <div className="field">
              <label htmlFor="providerExtra">{providerCfg.extraLabel}</label>
              <input id="providerExtra" name="providerExtra"
                type={form.providerName === 'LegacyLink' ? 'password' : 'text'}
                value={form.providerExtra}
                onChange={handleChange}
                placeholder={providerCfg.extraPlaceholder}
                required />
            </div>
          )}
        </div>
      </fieldset>

      {error && <div className="error-box">{error}</div>}

      <button type="submit" className="btn-primary" disabled={loading}>
        {loading ? 'Bezig met registreren…' : 'Registreren'}
      </button>
    </form>
  )
}
