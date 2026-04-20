// File-backed user profile registry for rp-widget-demo (Majestic Wine).
//
// Every successful OIDC callback upserts the user's claims into a single
// JSON file on disk. The file lives on a bind-mounted volume so profiles
// survive container restarts.
//
// Shape:
//   {
//     "users": [
//       {
//         "sub": "<claim_hash from auth-op>",
//         "provider": "authop" | "keycloak",
//         "email": "...",
//         "name": "...",
//         "given_name": "...",
//         "family_name": "...",
//         "birth_date": "YYYY-MM-DD",
//         "nationality": "AU",
//         "firstSeenAt": "2026-04-20T11:00:00.000Z",
//         "lastSeenAt": "2026-04-20T11:00:00.000Z",
//         "loginCount": 3
//       }
//     ]
//   }
//
// Atomic writes via tmp-file-and-rename. Reads are from an in-memory
// mirror that gets rebuilt on each load() call, which runs on construction
// and after every upsert. Single-file registry is fine at demo scale; if
// the stack ever needs real user management it should move to a proper DB.

const fs = require('fs');
const path = require('path');

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
   *  login stats, and persists. Returns the saved record. */
  upsert(profile) {
    if (!profile || !profile.sub) {
      throw new Error('userStore.upsert: profile.sub is required');
    }
    const now = new Date().toISOString();
    const existing = this.users.find((u) => u.sub === profile.sub);
    let saved;
    if (existing) {
      saved = Object.assign({}, existing, profile, {
        firstSeenAt: existing.firstSeenAt || now,
        lastSeenAt: now,
        loginCount: (existing.loginCount || 0) + 1,
      });
      this.users = this.users.map((u) => (u.sub === profile.sub ? saved : u));
    } else {
      saved = Object.assign({}, profile, {
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
