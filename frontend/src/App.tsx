import { Link, NavLink, Route, Routes } from 'react-router-dom';
import { useAuth } from './auth';
import EventsPage from './pages/EventsPage';
import EventPage from './pages/EventPage';
import LoginPage from './pages/LoginPage';
import MyBookingsPage from './pages/MyBookingsPage';

export default function App() {
  const { user, signOut } = useAuth();

  return (
    <div className="shell">
      <header className="topbar">
        <Link to="/" className="brand" aria-label="SeatLock home">
          <span className="brand-mark" aria-hidden="true" />
          SeatLock
        </Link>
        <nav className="nav">
          <NavLink to="/" end>
            Events
          </NavLink>
          {user && <NavLink to="/bookings">My bookings</NavLink>}
          {user ? (
            <button type="button" className="link-button" onClick={signOut}>
              Sign out {user.displayName}
            </button>
          ) : (
            <NavLink to="/login">Sign in</NavLink>
          )}
        </nav>
      </header>

      <main className="content">
        <Routes>
          <Route path="/" element={<EventsPage />} />
          <Route path="/events/:id" element={<EventPage />} />
          <Route path="/login" element={<LoginPage />} />
          <Route path="/bookings" element={<MyBookingsPage />} />
          <Route path="*" element={<p className="notice">This page does not exist.</p>} />
        </Routes>
      </main>
    </div>
  );
}
