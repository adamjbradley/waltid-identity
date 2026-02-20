import type {NextApiRequest, NextApiResponse} from "next";

type ResponseData = {};

export default function handler(
  req: NextApiRequest,
  res: NextApiResponse<ResponseData>
) {
  // Use dynamic access to read runtime env vars instead of build-time inlined values.
  // Next.js replaces static process.env.NEXT_PUBLIC_* references at build time.
  const env = process.env;
  const get = (key: string) => env[key];
  res.status(200).json({
    NEXT_PUBLIC_VC_REPO: get('NEXT_PUBLIC_VC_REPO'),
    NEXT_PUBLIC_ISSUER: get('NEXT_PUBLIC_ISSUER'),
    NEXT_PUBLIC_VERIFIER: get('NEXT_PUBLIC_VERIFIER'),
    NEXT_PUBLIC_VERIFIER2: get('NEXT_PUBLIC_VERIFIER2'),
    NEXT_PUBLIC_VERIFIER2_CLIENT_ID: get('NEXT_PUBLIC_VERIFIER2_CLIENT_ID'),
    NEXT_PUBLIC_VERIFIER2_SIGNING_KEY: get('NEXT_PUBLIC_VERIFIER2_SIGNING_KEY'),
    NEXT_PUBLIC_VERIFIER2_X5C: get('NEXT_PUBLIC_VERIFIER2_X5C'),
    NEXT_PUBLIC_WALLET: get('NEXT_PUBLIC_WALLET'),
    NEXT_PUBLIC_ISSUER_REGISTRAR_ENABLED: get('NEXT_PUBLIC_ISSUER_REGISTRAR_ENABLED'),
    NEXT_PUBLIC_RP_REGISTRAR_ENABLED: get('NEXT_PUBLIC_RP_REGISTRAR_ENABLED'),
    NEXT_PUBLIC_STATUS_LISTS_ENABLED: get('NEXT_PUBLIC_STATUS_LISTS_ENABLED'),
    NEXT_PUBLIC_PORTAL_CALLBACK_URL: get('NEXT_PUBLIC_PORTAL_CALLBACK_URL'),
  });
}
