import { useState, type FormEvent } from 'react';
import { useLocation, useNavigate } from 'react-router-dom';
import { ApiError } from '../api';
import { useAuth } from '../auth';

export default function LoginPage() {
  const { signIn, signUp } = useAuth();
  const navigate = useNavigate();
  const location = useLocation();
  const returnTo = (location.state as { from?: string } | null)?.from ?? '/';

  const [mode, setMode] = useState<'signin' | 'signup'>('signin');
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [name, setName] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [fieldErrors, setFieldErrors] = useState<Record<string, string>>({});
  const [busy, setBusy] = useState(false);

  async function submit(e: FormEvent) {
    e.preventDefault();
    setBusy(true);
    setError(null);
    setFieldErrors({});
    try {
      if (mode === 'signin') await signIn(email, password);
      else await signUp(email, password, name);
      navigate(returnTo, { replace: true });
    } catch (err) {
      const apiErr = err as ApiError;
      setError(apiErr.message);
      setFieldErrors(apiErr.fieldErrors ?? {});
    } finally {
      setBusy(false);
    }
  }

  return (
    <section className="auth">
      <h1>{mode === 'signin' ? 'Sign in' : 'Create an account'}</h1>
      <p className="muted">
        {mode === 'signin' ? 'Sign in to hold and book seats.' : 'Use any email. Passwords need 8 or more characters.'}
      </p>

      <form onSubmit={submit} className="form" noValidate>
        {mode === 'signup' && (
          <label>
            Name
            <input value={name} onChange={(e) => setName(e.target.value)} autoComplete="name" required />
            {fieldErrors.displayName && <span className="field-error">Name {fieldErrors.displayName}</span>}
          </label>
        )}
        <label>
          Email
          <input type="email" value={email} onChange={(e) => setEmail(e.target.value)} autoComplete="email" required />
          {fieldErrors.email && <span className="field-error">Email {fieldErrors.email}</span>}
        </label>
        <label>
          Password
          <input
            type="password"
            value={password}
            onChange={(e) => setPassword(e.target.value)}
            autoComplete={mode === 'signin' ? 'current-password' : 'new-password'}
            required
          />
          {fieldErrors.password && <span className="field-error">Password {fieldErrors.password}</span>}
        </label>

        {error && !Object.keys(fieldErrors).length && <p className="notice notice-error">{error}</p>}

        <button type="submit" className="button button-primary" disabled={busy}>
          {busy ? 'Working' : mode === 'signin' ? 'Sign in' : 'Create account'}
        </button>
      </form>

      <button
        type="button"
        className="link-button"
        onClick={() => {
          setMode(mode === 'signin' ? 'signup' : 'signin');
          setError(null);
          setFieldErrors({});
        }}
      >
        {mode === 'signin' ? 'New here? Create an account' : 'Already have an account? Sign in'}
      </button>

      <p className="demo-hint">
        Demo account: <code>student@seatlock.dev</code> with password <code>student12345</code>
      </p>
    </section>
  );
}
