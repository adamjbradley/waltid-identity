import type { NextApiRequest, NextApiResponse } from 'next';
import axios from 'axios';

// Server-side proxy for admin API calls.
// Avoids CORS and Origin-based CSRF blocks from the external reverse proxy.
export default async function handler(req: NextApiRequest, res: NextApiResponse) {
  const { path, ...queryParams } = req.query;
  const pathStr = Array.isArray(path) ? path.join('/') : path || '';

  // Determine target based on first path segment
  const env = process.env;
  const get = (key: string) => env[key];
  let targetBase: string | undefined;

  // Prefer *_INTERNAL_URL for server-side calls — keeps traffic on the Docker network
  // and avoids the portal needing to trust Caddy's internal TLS CA.
  if (pathStr.startsWith('issuer/')) {
    targetBase = get('ISSUER_INTERNAL_URL') || get('NEXT_PUBLIC_ISSUER');
  } else if (pathStr.startsWith('verifier2/')) {
    targetBase = get('VERIFIER2_INTERNAL_URL') || get('NEXT_PUBLIC_VERIFIER2');
  } else if (pathStr.startsWith('verifier/')) {
    targetBase = get('VERIFIER_INTERNAL_URL') || get('NEXT_PUBLIC_VERIFIER');
  } else {
    return res.status(400).json({ error: 'Unknown service prefix. Use issuer/, verifier/, or verifier2/' });
  }

  if (!targetBase) {
    return res.status(500).json({ error: 'Service URL not configured' });
  }

  // Strip the service prefix from the path
  const servicePath = pathStr.replace(/^(issuer|verifier2|verifier)\//, '');
  const targetUrl = `${targetBase}/${servicePath}`;

  try {
    const response = await axios({
      method: req.method as any,
      url: targetUrl,
      data: req.method !== 'GET' && req.method !== 'DELETE' ? req.body : undefined,
      params: req.method === 'GET' ? queryParams : undefined,
      headers: { 'Content-Type': 'application/json' },
      validateStatus: () => true,
    });

    res.status(response.status).json(response.data);
  } catch (e: any) {
    res.status(502).json({ error: e.message || 'Proxy request failed' });
  }
}
