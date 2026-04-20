// File-backed user profile registry for rp-widget-demo (Majestic Wine).
//
// Every successful OIDC callback upserts the user's claims into a single
// JSON file on disk. The file lives on a bind-mounted volume so profiles
// survive container restarts.
//
// Shape (authop provider — after rp-scope-hints design):
//   {
//     "users": [
//       {
//         "sub": "<claim_hash from auth-op>",
//         "provider": "authop",
//         "kyc_verified": true,
//         "age_over_18": true,
//         "age_over_21": false,
//         "firstSeenAt": "2026-04-20T11:00:00.000Z",
//         "lastSeenAt": "2026-04-20T11:00:00.000Z",
//         "loginCount": 3
//       }
//     ]
//   }
//
// Shape (keycloak provider): same plus name/given_name/family_name/email —
// Keycloak doesn't route through the auth-op scope projector so we still
// persist the standard OIDC profile claims.
//
// For the authop provider the store enforces a strict field allowlist on
// upsert (sub + kyc_verified + age flags + accounting fields). Any PII that
// accidentally slips into `profile` never makes it onto disk. See
// docs/plans/2026-04-20-rp-scope-hints-design.md for the privacy contract.
//
// Atomic writes via tmp-file-and-rename. Reads are from an in-memory
// mirror that gets rebuilt on each load() call, which runs on construction
// and after every upsert. Single-file registry is fine at demo scale; if
// the stack ever needs real user management it should move to a proper DB.

const fs = require('fs');
const path = require('path');

// Fields permitted on authop records. Anything else on the incoming profile
// is stripped at upsert time — defence in depth if a future refactor
// regresses and sends PII through.
const AUTHOP_ALLOWED_FIELDS = new Set([
  'sub', 'provider',
  'kyc_verified', 'age_over_18', 'age_over_21',
  'firstSeenAt', 'lastSeenAt', 'loginCount',
]);

class UserStore {
  constructor(filePath) {
    this.filePath = filePath;
    this.users = [];
    this._load();
  }

  _load() {
    try {
      if (!fs.existsSync(this.filePath)) {
        this.users = [];
        return;
      }
      const raw = fs.readFileSync(this.filePath, 'utf-8');
      const parsed = JSON.parse(raw);
      this.users = Array.isArray(parsed.users) ? parsed.users : [];
    } catch (err) {
      console.warn('[userStore] load failed, starting empty:', err.message);
      this.users = [];
    }
  }

  _persist() {
    const dir = path.dirname(this.filePath);
    try {
      fs.mkdirSync(dir, { recursive: true });
    } catch (_) { /* already exists */ }
    const tmp = this.filePath + '.tmp';
    fs.writeFileSync(tmp, JSON.stringify({ users: this.users }, null, 2));
    fs.renameSync(tmp, this.filePath);
  }

  /** Upsert a user by sub. Merges new claims over existing entry, bumps
   *  login stats, and persists. For authop records an allowlist strips
   *  any non-permitted fields before write. Returns the saved record. */
  upsert(profile) {
    if (!profile || !profile.sub) {
      throw new Error('userStore.upsert: profile.sub is required');
    }
    const now = new Date().toISOString();
    // Allowlist filter for authop. Drops anything that isn't in the
    // privacy-approved shape — a regression that sends e.g. `email` for an
    // authop login gets silently truncated instead of landing on disk.
    const input = (profile.provider === 'authop')
      ? Object.fromEntries(Object.entries(profile).filter(([k]) => AUTHOP_ALLOWED_FIELDS.has(k)))
      : profile;
    const existing = this.users.find((u) => u.sub === input.sub);
    let saved;
    if (existing) {
      // Prune legacy PII off existing authop records on the next login so
      // records that pre-date this change converge to the new shape.
      const base = (input.provider === 'authop')
        ? Object.fromEntries(Object.entries(existing).filter(([k]) => AUTHOP_ALLOWED_FIELDS.has(k)))
        : existing;
      saved = Object.assign({}, base, input, {
        firstSeenAt: existing.firstSeenAt || now,
        lastSeenAt: now,
        loginCount: (existing.loginCount || 0) + 1,
      });
      this.users = this.users.map((u) => (u.sub === input.sub ? saved : u));
    } else {
      saved = Object.assign({}, input, {
        firstSeenAt: now,
        lastSeenAt: now,
        loginCount: 1,
      });
      this.users = this.users.concat([saved]);
    }
    this._persist();
    return saved;
  }

  /** All stored users, newest-login first. */
  list() {
    return this.users
      .slice()
      .sort((a, b) => (b.lastSeenAt || '').localeCompare(a.lastSeenAt || ''));
  }

  /** Count of stored users (for tiny metrics in the UI). */
  count() {
    return this.users.length;
  }
}

module.exports = { UserStore };
