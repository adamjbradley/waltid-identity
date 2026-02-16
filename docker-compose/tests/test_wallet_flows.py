"""
E2E tests for wallet issuance and verification flows.

Tests all four combinations:
  1. Non-MT issuance + PD verification (legacy verifier)
  2. Non-MT issuance + DCQL verification (modern verifier)
  3. MT issuance + DCQL verification (modern verifier)
  4. MT issuance + PD verification (legacy verifier)

Prerequisites:
  - All Docker Compose services running: docker compose --profile identity up -d
  - ISSUER_REGISTRAR_ENABLED=true in .env (for MT tests)

Usage:
  cd docker-compose && python3 tests/test_wallet_flows.py -v
"""

import http.client
import json
import time
import unittest
import uuid
from urllib.parse import urlparse, parse_qs, urlencode

# ---------------------------------------------------------------------------
# Constants
# ---------------------------------------------------------------------------

WALLET_API = "localhost:7001"
ISSUER_API = "localhost:7002"
VERIFIER_API = "localhost:7003"   # Legacy (PD)
VERIFIER_API2 = "localhost:7004"  # Modern (DCQL)

ISSUER_KEY = {
    "type": "jwk",
    "jwk": {
        "kty": "OKP",
        "d": "mDhpwaH6JYSrD2Bq7Cs-pzmsjlLj4EOhxyI-9DM1mFI",
        "crv": "Ed25519",
        "kid": "Vzx7l5fh56F3Pf9aR3DECU5BwfrY6ZJe05aiWYWzan8",
        "x": "T3T4-u1Xz3vAV2JwPNxWfs4pik_JLiArz_WTCvrCFUM",
    },
}

ISSUER_DID = "did:key:z6MkjoRhq1jSNJdLiruSXrFFxagqrztZaXHqHGUTKJbcNywp"

CREDENTIAL_DATA = {
    "given_name": "Alice",
    "family_name": "Test",
    "birth_date": "1990-01-01",
}

SELECTIVE_DISCLOSURE = {
    "fields": {
        "given_name": {"sd": True},
        "family_name": {"sd": True},
        "birth_date": {"sd": True},
    }
}

MAPPING = {
    "id": "<uuid>",
    "issuer": {"id": "<issuerDid>"},
    "credentialSubject": {"id": "<subjectDid>"},
    "issuanceDate": "<timestamp>",
    "expirationDate": "<timestamp-in:365d>",
}

# ---------------------------------------------------------------------------
# HTTP helpers
# ---------------------------------------------------------------------------


def http_request(host, method, path, body=None, headers=None, content_type=None):
    """Make an HTTP request and return (status_code, response_body_string)."""
    conn = http.client.HTTPConnection(host, timeout=30)
    hdrs = headers or {}
    send_body = None

    if body is not None:
        if isinstance(body, dict) or isinstance(body, list):
            send_body = json.dumps(body)
            if content_type is None:
                content_type = "application/json"
        else:
            send_body = str(body)
            if content_type is None:
                content_type = "text/plain"

    if content_type:
        hdrs["Content-Type"] = content_type

    conn.request(method, path, body=send_body, headers=hdrs)
    resp = conn.getresponse()
    data = resp.read().decode("utf-8")
    conn.close()
    return resp.status, data


def parse_url_params(url):
    """Extract query parameters from a URL."""
    parsed = urlparse(url)
    return {k: v[0] if len(v) == 1 else v for k, v in parse_qs(parsed.query).items()}


# ---------------------------------------------------------------------------
# Auth helpers
# ---------------------------------------------------------------------------


def register_user(email, password, name):
    """Register a new wallet user."""
    status, body = http_request(
        WALLET_API,
        "POST",
        "/wallet-api/auth/create",
        body={"name": name, "email": email, "password": password, "type": "email"},
    )
    assert status in (201, 200), f"register_user failed: {status} {body}"
    return body


def login_user(email, password):
    """Login and return the auth token."""
    status, body = http_request(
        WALLET_API,
        "POST",
        "/wallet-api/auth/login",
        body={"email": email, "password": password, "type": "email"},
    )
    assert status == 200, f"login_user failed: {status} {body}"
    data = json.loads(body)
    return data.get("token", data.get("id", body))


def auth_headers(token):
    """Return Authorization header dict."""
    return {"Authorization": f"Bearer {token}"}


# ---------------------------------------------------------------------------
# Wallet helpers
# ---------------------------------------------------------------------------


def get_wallet_id(token):
    """Get the first wallet ID for the authenticated user."""
    status, body = http_request(
        WALLET_API,
        "GET",
        "/wallet-api/wallet/accounts/wallets",
        headers=auth_headers(token),
    )
    assert status == 200, f"get_wallet_id failed: {status} {body}"
    data = json.loads(body)
    wallets = data.get("wallets", data) if isinstance(data, dict) else data
    if isinstance(wallets, list) and len(wallets) > 0:
        return wallets[0].get("id", wallets[0])
    raise AssertionError(f"No wallets found: {body}")


def get_default_did(token, wallet_id):
    """Get the default DID for the wallet."""
    status, body = http_request(
        WALLET_API,
        "GET",
        f"/wallet-api/wallet/{wallet_id}/dids",
        headers=auth_headers(token),
    )
    assert status == 200, f"get_default_did failed: {status} {body}"
    dids = json.loads(body)
    if isinstance(dids, list) and len(dids) > 0:
        return dids[0].get("did", dids[0])
    raise AssertionError(f"No DIDs found: {body}")


def list_credentials(token, wallet_id):
    """List all credentials in the wallet."""
    status, body = http_request(
        WALLET_API,
        "GET",
        f"/wallet-api/wallet/{wallet_id}/credentials",
        headers=auth_headers(token),
    )
    assert status == 200, f"list_credentials failed: {status} {body}"
    return json.loads(body)


# ---------------------------------------------------------------------------
# Issuance helpers
# ---------------------------------------------------------------------------


def issue_sdjwt(credential_data, selective_disclosure, mapping=None, mt_issuer_id=None):
    """Issue an SD-JWT credential. Returns the offer URL string."""
    request_body = {
        "credentialConfigurationId": "identity_credential_vc+sd-jwt",
        "credentialData": credential_data,
        "mapping": mapping or MAPPING,
        "selectiveDisclosure": selective_disclosure,
    }

    if mt_issuer_id:
        path = f"/issuers/{mt_issuer_id}/openid4vc/sdjwt/issue"
        host = ISSUER_API
    else:
        request_body["issuerKey"] = ISSUER_KEY
        request_body["issuerDid"] = ISSUER_DID
        path = "/openid4vc/sdjwt/issue"
        host = ISSUER_API

    status, body = http_request(host, "POST", path, body=request_body)
    assert status in (200, 201), f"issue_sdjwt failed: {status} {body}"
    return body.strip().strip('"')


def claim_offer(token, wallet_id, offer_url):
    """Claim a credential offer in the wallet."""
    status, body = http_request(
        WALLET_API,
        "POST",
        f"/wallet-api/wallet/{wallet_id}/exchange/useOfferRequest",
        body=offer_url,
        headers=auth_headers(token),
        content_type="text/plain",
    )
    assert status == 200, f"claim_offer failed: {status} {body}"
    return json.loads(body) if body.strip().startswith(("[", "{")) else body


# ---------------------------------------------------------------------------
# PD Verification helpers (legacy verifier, port 7003)
# ---------------------------------------------------------------------------


def create_pd_verification(request_credentials):
    """Create a PD-based verification session on the legacy verifier.

    Returns (verification_url, session_id).
    """
    status, body = http_request(
        VERIFIER_API,
        "POST",
        "/openid4vc/verify",
        body={"request_credentials": request_credentials},
    )
    assert status == 200, f"create_pd_verification failed: {status} {body}"
    url = body.strip().strip('"')
    params = parse_url_params(url)
    session_id = params.get("state", "")
    return url, session_id


def get_pd_session_result(session_id):
    """Get the result of a PD verification session."""
    status, body = http_request(
        VERIFIER_API,
        "GET",
        f"/openid4vc/session/{session_id}",
    )
    assert status == 200, f"get_pd_session_result failed: {status} {body}"
    return json.loads(body)


# ---------------------------------------------------------------------------
# DCQL Verification helpers (modern verifier, port 7004)
# ---------------------------------------------------------------------------


def create_dcql_verification(dcql_query):
    """Create a DCQL verification session on the modern verifier.

    Returns (bootstrap_url, session_id).
    """
    request_body = {
        "flow_type": "cross_device",
        "core_flow": {
            "dcql_query": dcql_query,
        },
    }
    status, body = http_request(
        VERIFIER_API2,
        "POST",
        "/verification-session/create",
        body=request_body,
    )
    assert status in (200, 201), f"create_dcql_verification failed: {status} {body}"
    data = json.loads(body)
    session_id = data.get("sessionId", "")
    bootstrap_url = data.get("bootstrapAuthorizationRequestUrl", "")
    return bootstrap_url, session_id


def get_dcql_session_info(session_id):
    """Get session info from the modern verifier."""
    status, body = http_request(
        VERIFIER_API2,
        "GET",
        f"/verification-session/{session_id}/info",
    )
    assert status == 200, f"get_dcql_session_info failed: {status} {body}"
    return json.loads(body)


# ---------------------------------------------------------------------------
# Wallet presentation helpers
# ---------------------------------------------------------------------------


def resolve_presentation(token, wallet_id, url):
    """Resolve a presentation request URL via the wallet."""
    status, body = http_request(
        WALLET_API,
        "POST",
        f"/wallet-api/wallet/{wallet_id}/exchange/resolvePresentationRequest",
        body=url,
        headers=auth_headers(token),
        content_type="text/plain",
    )
    assert status == 200, f"resolve_presentation failed: {status} {body}"
    return body


def match_credentials_pd(token, wallet_id, presentation_definition):
    """Match wallet credentials against a PresentationDefinition."""
    pd_obj = presentation_definition
    if isinstance(pd_obj, str):
        pd_obj = json.loads(pd_obj)
    status, body = http_request(
        WALLET_API,
        "POST",
        f"/wallet-api/wallet/{wallet_id}/exchange/matchCredentialsForPresentationDefinition",
        body=pd_obj,
        headers=auth_headers(token),
    )
    assert status == 200, f"match_credentials_pd failed: {status} {body}"
    return json.loads(body)


def match_credentials_dcql(token, wallet_id, dcql_query):
    """Match wallet credentials against a DCQL query."""
    query_obj = dcql_query
    if isinstance(query_obj, str):
        query_obj = json.loads(query_obj)
    status, body = http_request(
        WALLET_API,
        "POST",
        f"/wallet-api/wallet/{wallet_id}/exchange/matchCredentialsForDcqlQuery",
        body=query_obj,
        headers=auth_headers(token),
    )
    assert status == 200, f"match_credentials_dcql failed: {status} {body}"
    return json.loads(body)


def present_credentials(token, wallet_id, did, request_url, credential_ids):
    """Present credentials to a verifier via the wallet."""
    status, body = http_request(
        WALLET_API,
        "POST",
        f"/wallet-api/wallet/{wallet_id}/exchange/usePresentationRequest",
        body={
            "did": did,
            "presentationRequest": request_url,
            "selectedCredentials": credential_ids,
        },
        headers=auth_headers(token),
    )
    assert status == 200, f"present_credentials failed: {status} {body}"
    return json.loads(body) if body.strip().startswith(("{", "[")) else body


# ---------------------------------------------------------------------------
# MT (multi-tenant) issuer admin helpers
# ---------------------------------------------------------------------------


def register_issuer_tenant(legal_name, country, domain, email):
    """Register a new issuer tenant. Returns tenant detail JSON."""
    status, body = http_request(
        ISSUER_API,
        "POST",
        "/admin/issuer",
        body={
            "legalName": legal_name,
            "country": country,
            "domain": domain,
            "contactEmail": email,
        },
    )
    assert status in (200, 201), f"register_issuer_tenant failed: {status} {body}"
    return json.loads(body)


def generate_tenant_certificate(issuer_id):
    """Generate signing key + certificate for a tenant. Returns updated tenant detail."""
    status, body = http_request(
        ISSUER_API,
        "POST",
        f"/admin/issuer/{issuer_id}/certificate/generate",
    )
    assert status in (200, 201), f"generate_tenant_certificate failed: {status} {body}"
    return json.loads(body)


def delete_issuer_tenant(issuer_id):
    """Delete an issuer tenant (best-effort cleanup)."""
    status, body = http_request(
        ISSUER_API,
        "DELETE",
        f"/admin/issuer/{issuer_id}",
    )
    return status, body


# ===========================================================================
# Non-MT Flow Tests
# ===========================================================================


class NonMTFlowTests(unittest.TestCase):
    """Test non-multi-tenant issuance with PD and DCQL verification."""

    @classmethod
    def setUpClass(cls):
        cls.run_id = uuid.uuid4().hex[:8]
        cls.email = f"nonmt-{cls.run_id}@test.com"
        cls.password = "testpass123"
        register_user(cls.email, cls.password, f"NonMT-{cls.run_id}")
        cls.token = login_user(cls.email, cls.password)
        cls.wallet_id = get_wallet_id(cls.token)
        cls.did = get_default_did(cls.token, cls.wallet_id)
        cls.credential_ids = []

    # -- Issue + PD Verify --------------------------------------------------

    def test_01_issue_sdjwt_and_hold(self):
        """Issue an SD-JWT credential via the non-MT issuer and claim it."""
        offer_url = issue_sdjwt(CREDENTIAL_DATA, SELECTIVE_DISCLOSURE)
        self.assertTrue(
            offer_url.startswith("openid-credential-offer://"),
            f"Unexpected offer URL: {offer_url}",
        )

        claim_offer(self.token, self.wallet_id, offer_url)

        creds = list_credentials(self.token, self.wallet_id)
        self.assertTrue(len(creds) >= 1, "No credentials in wallet after claiming")
        # Store the most recent credential ID
        if isinstance(creds, list) and isinstance(creds[0], dict):
            self.__class__.credential_ids.append(creds[0].get("id", creds[0]))
        else:
            self.__class__.credential_ids.append(creds[0])

    def test_02_verify_via_presentation_definition(self):
        """Verify a held credential via PD (legacy verifier)."""
        self.assertTrue(len(self.credential_ids) >= 1, "No credential to verify")

        # Create PD verification session
        request_credentials = [{"format": "vc+sd-jwt", "type": "VerifiableCredential"}]
        verify_url, session_id = create_pd_verification(request_credentials)
        self.assertTrue(session_id, "No session_id from PD verification")

        # Wallet resolves the presentation request
        resolved = resolve_presentation(self.token, self.wallet_id, verify_url)
        self.assertTrue(resolved, "Empty resolved presentation")

        # Parse the resolved URL to extract presentation_definition
        resolved_str = resolved.strip().strip('"')
        resolved_params = parse_url_params(resolved_str)
        self.assertIn(
            "presentation_definition",
            resolved_params,
            f"No presentation_definition in resolved URL params: {list(resolved_params.keys())}",
        )

        # Match credentials
        pd_json = json.loads(resolved_params["presentation_definition"])
        matched = match_credentials_pd(self.token, self.wallet_id, pd_json)
        matched_list = matched if isinstance(matched, list) else [matched]
        self.assertTrue(len(matched_list) >= 1, "No credentials matched PD")

        # Extract credential IDs from matched results
        cred_ids_to_present = []
        for m in matched_list:
            if isinstance(m, dict) and "id" in m:
                cred_ids_to_present.append(m["id"])
            elif isinstance(m, str):
                cred_ids_to_present.append(m)
        if not cred_ids_to_present:
            cred_ids_to_present = self.credential_ids[:1]

        # Present credentials
        result = present_credentials(
            self.token, self.wallet_id, self.did, resolved_str, cred_ids_to_present
        )

        # Allow a moment for async processing
        time.sleep(1)

        # Check session result
        session = get_pd_session_result(session_id)
        self.assertTrue(
            session.get("verificationResult", False),
            f"PD verification failed: {json.dumps(session, indent=2)[:500]}",
        )

    # -- Issue + DCQL Verify ------------------------------------------------

    def test_03_issue_sdjwt_for_dcql(self):
        """Issue a second SD-JWT credential for DCQL verification."""
        cred_data = {
            "given_name": "Bob",
            "family_name": "DcqlTest",
            "birth_date": "1985-06-15",
        }
        offer_url = issue_sdjwt(cred_data, SELECTIVE_DISCLOSURE)
        self.assertTrue(
            offer_url.startswith("openid-credential-offer://"),
            f"Unexpected offer URL: {offer_url}",
        )

        claim_offer(self.token, self.wallet_id, offer_url)

        creds = list_credentials(self.token, self.wallet_id)
        self.assertTrue(len(creds) >= 2, "Expected at least 2 credentials")
        if isinstance(creds, list) and isinstance(creds[0], dict):
            self.__class__.credential_ids.append(creds[0].get("id", creds[0]))
        else:
            self.__class__.credential_ids.append(creds[0])

    def test_04_verify_via_dcql(self):
        """Verify a held credential via DCQL (modern verifier)."""
        self.assertTrue(len(self.credential_ids) >= 2, "No credential to verify")

        dcql_query = {
            "credentials": [
                {
                    "id": "TestCred",
                    "format": "dc+sd-jwt",
                    "meta": {
                        "vct_values": [
                            f"http://{ISSUER_API}/draft13/identity_credential"
                        ]
                    },
                    "claims": [
                        {"path": ["family_name"]},
                        {"path": ["given_name"]},
                    ],
                }
            ]
        }

        bootstrap_url, session_id = create_dcql_verification(dcql_query)
        self.assertTrue(session_id, "No session_id from DCQL verification")
        self.assertTrue(bootstrap_url, "No bootstrap URL")

        # Wallet resolves the presentation request
        resolved = resolve_presentation(self.token, self.wallet_id, bootstrap_url)
        self.assertTrue(resolved, "Empty resolved presentation")
        resolved_str = resolved.strip().strip('"')

        # DCQL path should NOT have presentation_definition
        resolved_params = parse_url_params(resolved_str)
        self.assertNotIn(
            "presentation_definition",
            resolved_params,
            "DCQL flow should not contain presentation_definition",
        )

        # Match credentials via DCQL
        matched = match_credentials_dcql(self.token, self.wallet_id, dcql_query)
        matched_list = matched if isinstance(matched, list) else [matched]
        self.assertTrue(len(matched_list) >= 1, "No credentials matched DCQL")

        # Extract credential IDs
        cred_ids_to_present = []
        for m in matched_list:
            if isinstance(m, dict) and "id" in m:
                cred_ids_to_present.append(m["id"])
            elif isinstance(m, str):
                cred_ids_to_present.append(m)
        if not cred_ids_to_present:
            cred_ids_to_present = self.credential_ids[-1:]

        # Present credentials
        result = present_credentials(
            self.token, self.wallet_id, self.did, resolved_str, cred_ids_to_present
        )

        time.sleep(1)

        # Check session result
        session = get_dcql_session_info(session_id)
        status = session.get("status", "")
        self.assertEqual(
            status,
            "SUCCESSFUL",
            f"DCQL verification not successful: {json.dumps(session, indent=2)[:500]}",
        )


# ===========================================================================
# MT (Multi-Tenant) Flow Tests
# ===========================================================================


class MTFlowTests(unittest.TestCase):
    """Test multi-tenant issuance with DCQL and PD verification."""

    @classmethod
    def setUpClass(cls):
        cls.run_id = uuid.uuid4().hex[:8]
        cls.email = f"mt-{cls.run_id}@test.com"
        cls.password = "testpass123"
        register_user(cls.email, cls.password, f"MT-{cls.run_id}")
        cls.token = login_user(cls.email, cls.password)
        cls.wallet_id = get_wallet_id(cls.token)
        cls.did = get_default_did(cls.token, cls.wallet_id)
        cls.credential_ids = []

        # Create test issuer tenant
        cls.tenant = register_issuer_tenant(
            legal_name=f"Test Issuer {cls.run_id}",
            country="AU",
            domain=f"test-{cls.run_id}.example.com",
            email=f"admin-{cls.run_id}@example.com",
        )
        cls.issuer_id = cls.tenant["id"]

        # Generate signing key + certificate
        cls.tenant = generate_tenant_certificate(cls.issuer_id)

    @classmethod
    def tearDownClass(cls):
        try:
            delete_issuer_tenant(cls.issuer_id)
        except Exception:
            pass

    # -- MT Issue + DCQL Verify ---------------------------------------------

    def test_01_mt_issue_and_hold(self):
        """Issue an SD-JWT credential via the MT issuer and claim it."""
        cred_data = {
            "given_name": "Charlie",
            "family_name": "Tenant",
            "birth_date": "1992-03-20",
        }
        # MT issuance: issuerKey/issuerDid omitted — tenant keys used
        offer_url = issue_sdjwt(
            cred_data, SELECTIVE_DISCLOSURE, mt_issuer_id=self.issuer_id
        )
        self.assertTrue(
            offer_url.startswith("openid-credential-offer://"),
            f"Unexpected MT offer URL: {offer_url}",
        )

        claim_offer(self.token, self.wallet_id, offer_url)

        creds = list_credentials(self.token, self.wallet_id)
        self.assertTrue(len(creds) >= 1, "No credentials after MT issuance")
        if isinstance(creds, list) and isinstance(creds[0], dict):
            self.__class__.credential_ids.append(creds[0].get("id", creds[0]))
        else:
            self.__class__.credential_ids.append(creds[0])

    def test_02_mt_verify_via_dcql(self):
        """Verify MT-issued credential via DCQL (modern verifier)."""
        self.assertTrue(len(self.credential_ids) >= 1, "No credential to verify")

        # Use the tenant's issuer URL for vct_values
        dcql_query = {
            "credentials": [
                {
                    "id": "MTTestCred",
                    "format": "dc+sd-jwt",
                    "meta": {
                        "vct_values": [
                            f"http://{ISSUER_API}/issuers/{self.issuer_id}/draft13/identity_credential"
                        ]
                    },
                    "claims": [
                        {"path": ["family_name"]},
                        {"path": ["given_name"]},
                    ],
                }
            ]
        }

        bootstrap_url, session_id = create_dcql_verification(dcql_query)
        self.assertTrue(session_id, "No session_id from DCQL verification")
        self.assertTrue(bootstrap_url, "No bootstrap URL")

        # Wallet resolves
        resolved = resolve_presentation(self.token, self.wallet_id, bootstrap_url)
        self.assertTrue(resolved, "Empty resolved presentation")
        resolved_str = resolved.strip().strip('"')

        # Match via DCQL
        matched = match_credentials_dcql(self.token, self.wallet_id, dcql_query)
        matched_list = matched if isinstance(matched, list) else [matched]
        self.assertTrue(len(matched_list) >= 1, "No credentials matched DCQL")

        cred_ids_to_present = []
        for m in matched_list:
            if isinstance(m, dict) and "id" in m:
                cred_ids_to_present.append(m["id"])
            elif isinstance(m, str):
                cred_ids_to_present.append(m)
        if not cred_ids_to_present:
            cred_ids_to_present = self.credential_ids[-1:]

        # Present
        result = present_credentials(
            self.token, self.wallet_id, self.did, resolved_str, cred_ids_to_present
        )

        time.sleep(1)

        # Check result
        session = get_dcql_session_info(session_id)
        status = session.get("status", "")
        self.assertEqual(
            status,
            "SUCCESSFUL",
            f"MT DCQL verification not successful: {json.dumps(session, indent=2)[:500]}",
        )

    # -- MT Issue + PD Verify -----------------------------------------------

    def test_03_mt_issue_for_pd(self):
        """Issue a second MT credential for PD verification."""
        cred_data = {
            "given_name": "Dana",
            "family_name": "TenantPD",
            "birth_date": "1988-11-05",
        }
        offer_url = issue_sdjwt(
            cred_data, SELECTIVE_DISCLOSURE, mt_issuer_id=self.issuer_id
        )
        self.assertTrue(
            offer_url.startswith("openid-credential-offer://"),
            f"Unexpected MT offer URL: {offer_url}",
        )

        claim_offer(self.token, self.wallet_id, offer_url)

        creds = list_credentials(self.token, self.wallet_id)
        self.assertTrue(len(creds) >= 2, "Expected at least 2 MT credentials")
        if isinstance(creds, list) and isinstance(creds[0], dict):
            self.__class__.credential_ids.append(creds[0].get("id", creds[0]))
        else:
            self.__class__.credential_ids.append(creds[0])

    def test_04_mt_verify_via_pd(self):
        """Verify MT-issued credential via PD (legacy verifier)."""
        self.assertTrue(len(self.credential_ids) >= 2, "No credential to verify")

        # Create PD verification session
        request_credentials = [{"format": "vc+sd-jwt", "type": "VerifiableCredential"}]
        verify_url, session_id = create_pd_verification(request_credentials)
        self.assertTrue(session_id, "No session_id from PD verification")

        # Wallet resolves
        resolved = resolve_presentation(self.token, self.wallet_id, verify_url)
        self.assertTrue(resolved, "Empty resolved presentation")
        resolved_str = resolved.strip().strip('"')

        # Extract and match PD
        resolved_params = parse_url_params(resolved_str)
        self.assertIn(
            "presentation_definition",
            resolved_params,
            f"No presentation_definition in resolved URL: {list(resolved_params.keys())}",
        )

        pd_json = json.loads(resolved_params["presentation_definition"])
        matched = match_credentials_pd(self.token, self.wallet_id, pd_json)
        matched_list = matched if isinstance(matched, list) else [matched]
        self.assertTrue(len(matched_list) >= 1, "No credentials matched PD")

        cred_ids_to_present = []
        for m in matched_list:
            if isinstance(m, dict) and "id" in m:
                cred_ids_to_present.append(m["id"])
            elif isinstance(m, str):
                cred_ids_to_present.append(m)
        if not cred_ids_to_present:
            cred_ids_to_present = self.credential_ids[-1:]

        # Present
        result = present_credentials(
            self.token, self.wallet_id, self.did, resolved_str, cred_ids_to_present
        )

        time.sleep(1)

        # Check session result
        session = get_pd_session_result(session_id)
        self.assertTrue(
            session.get("verificationResult", False),
            f"MT PD verification failed: {json.dumps(session, indent=2)[:500]}",
        )


# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------

if __name__ == "__main__":
    unittest.main()
