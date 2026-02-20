import type { NextApiRequest, NextApiResponse } from 'next';

// In-memory store for issuance session statuses (received via issuer callbacks).
// Keys are session IDs, values are the latest status payload.
const statusStore = new Map<string, { status: string; type: string; timestamp: number }>();

// Auto-expire entries after 10 minutes
const TTL_MS = 10 * 60 * 1000;

function cleanExpired() {
  const now = Date.now();
  const keys = Array.from(statusStore.keys());
  for (let i = 0; i < keys.length; i++) {
    const entry = statusStore.get(keys[i]);
    if (entry && now - entry.timestamp > TTL_MS) {
      statusStore.delete(keys[i]);
    }
  }
}

export default function handler(req: NextApiRequest, res: NextApiResponse) {
  const { sessionId } = req.query;
  if (!sessionId || typeof sessionId !== 'string') {
    return res.status(400).json({ error: 'Missing sessionId' });
  }

  if (req.method === 'POST') {
    // Receive callback from issuer API
    const body = req.body;
    const type = body?.type as string | undefined;
    const status = body?.data?.status as string | undefined;
    const newStatus = status || type || 'unknown';

    // Don't overwrite terminal statuses (SUCCESSFUL, UNSUCCESSFUL, etc.)
    const existing = statusStore.get(sessionId);
    const terminalStatuses = ['SUCCESSFUL', 'UNSUCCESSFUL', 'REJECTED_BY_USER', 'EXPIRED'];
    if (existing && terminalStatuses.includes(existing.status)) {
      cleanExpired();
      return res.status(200).json({ ok: true, ignored: true });
    }

    statusStore.set(sessionId, {
      status: newStatus,
      type: type || 'unknown',
      timestamp: Date.now(),
    });

    cleanExpired();
    return res.status(200).json({ ok: true });
  }

  if (req.method === 'GET') {
    // Poll for status
    const entry = statusStore.get(sessionId);
    if (!entry) {
      return res.status(200).json({ status: 'pending' });
    }
    return res.status(200).json({ status: entry.status, type: entry.type });
  }

  return res.status(405).json({ error: 'Method not allowed' });
}
