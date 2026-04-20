@file:OptIn(ExperimentalTime::class)

package id.walt.authop.passkey

import com.yubico.webauthn.AssertionRequest
import com.yubico.webauthn.AssertionResult
import com.yubico.webauthn.CredentialRepository
import com.yubico.webauthn.FinishAssertionOptions
import com.yubico.webauthn.FinishRegistrationOptions
import com.yubico.webauthn.RegisteredCredential
import com.yubico.webauthn.RegistrationResult
import com.yubico.webauthn.RelyingParty
import com.yubico.webauthn.StartAssertionOptions
import com.yubico.webauthn.StartRegistrationOptions
import com.yubico.webauthn.data.AuthenticatorSelectionCriteria
import com.yubico.webauthn.data.ByteArray as YubByteArray
import com.yubico.webauthn.data.PublicKeyCredential
import com.yubico.webauthn.data.PublicKeyCredentialCreationOptions
import com.yubico.webauthn.data.PublicKeyCredentialDescriptor
import com.yubico.webauthn.data.RelyingPartyIdentity
import com.yubico.webauthn.data.ResidentKeyRequirement
import com.yubico.webauthn.data.UserIdentity
import com.yubico.webauthn.data.UserVerificationRequirement
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import java.util.Optional
import kotlinx.datetime.Instant

/**
 * Wraps Yubico's [RelyingParty] for the auth-op citizens realm. Provides the
 * four ceremony entrypoints [startRegistration] / [finishRegistration] /
 * [startAssertion] / [finishAssertion] plus a conversion helper to persist
 * the accepted registration into [PasskeyStore].
 *
 * The relying party is configured strictly for the auth-op host:
 *   - rp.id  = "auth-op.theaustraliahack.com"
 *   - origin = "https://auth-op.theaustraliahack.com"
 *
 * Both are configurable on construction so tests / other deployments can
 * swap them without subclassing.
 *
 * The user handle is derived deterministically from the sub (the sub is
 * already a claim-hash, so its UTF-8 bytes make a stable ≤64-byte handle).
 * Yubico requires user handles to be at most 64 bytes — we fit comfortably
 * since sub is a hex-encoded SHA-256 hash (64 chars = 64 bytes).
 */
class PasskeyService(
    private val store: PasskeyStore,
    private val rpId: String,
    private val origin: String,
    private val rpName: String,
) {

    private val credentialRepository: CredentialRepository = PasskeyCredentialRepository(store)

    private val relyingParty: RelyingParty = RelyingParty.builder()
        .identity(RelyingPartyIdentity.builder().id(rpId).name(rpName).build())
        .credentialRepository(credentialRepository)
        .origins(setOf(origin))
        // Don't validate attestation statements — we accept self-attestation
        // and "none" attestation. Useful for demos and for platform
        // authenticators (Touch ID, Windows Hello) which default to "none".
        .allowUntrustedAttestation(true)
        .build()

    /** Build registration options for the browser's `navigator.credentials.create()`.
     *  Requires the wallet VP flow to have already established the sub, so
     *  that we can anchor the new credential to an existing identity. */
    fun startRegistration(sub: String, displayName: String): PublicKeyCredentialCreationOptions {
        val userId = YubByteArray(sub.toByteArray(Charsets.UTF_8))
        return relyingParty.startRegistration(
            StartRegistrationOptions.builder()
                .user(
                    UserIdentity.builder()
                        .name(sub)
                        .displayName(displayName.ifBlank { sub })
                        .id(userId)
                        .build()
                )
                .authenticatorSelection(
                    AuthenticatorSelectionCriteria.builder()
                        // Require a discoverable (resident) credential so the
                        // conditional-UI login flow can find the passkey
                        // without us knowing the sub in advance.
                        .residentKey(ResidentKeyRequirement.REQUIRED)
                        .userVerification(UserVerificationRequirement.PREFERRED)
                        .build()
                )
                .build()
        )
    }

    /** Verify the browser's registration response. On success, persists a
     *  [PasskeyCredential] to [PasskeyStore] and returns the stored entry. */
    fun finishRegistration(
        sub: String,
        displayName: String,
        requestOptions: PublicKeyCredentialCreationOptions,
        responseJson: String,
    ): PasskeyCredential {
        val response = PublicKeyCredential.parseRegistrationResponseJson(responseJson)
        val result: RegistrationResult = relyingParty.finishRegistration(
            FinishRegistrationOptions.builder()
                .request(requestOptions)
                .response(response)
                .build()
        )
        val credential = PasskeyCredential(
            sub = sub,
            credentialId = result.keyId.id.base64Url,
            publicKeyCose = result.publicKeyCose.base64Url,
            signatureCount = result.signatureCount,
            displayName = displayName.ifBlank { sub },
            createdAt = Instant.fromEpochMilliseconds(Clock.System.now().toEpochMilliseconds()),
        )
        store.save(credential)
        return credential
    }

    /** Build assertion options for login. We use an empty `allowCredentials`
     *  so the browser can surface any discoverable credential for this RP —
     *  which is what makes conditional UI work. */
    fun startAssertion(): AssertionRequest =
        relyingParty.startAssertion(
            StartAssertionOptions.builder()
                .userVerification(UserVerificationRequirement.PREFERRED)
                .build()
        )

    /** Verify the browser's assertion response and return the matching sub.
     *  Also bumps the signature counter for replay protection. */
    fun finishAssertion(
        request: AssertionRequest,
        responseJson: String,
    ): AssertionResolution {
        val response = PublicKeyCredential.parseAssertionResponseJson(responseJson)
        val result: AssertionResult = relyingParty.finishAssertion(
            FinishAssertionOptions.builder()
                .request(request)
                .response(response)
                .build()
        )
        val credentialId = result.credential.credentialId.base64Url
        store.bumpSignatureCount(credentialId, result.signatureCount)
        val sub = String(result.credential.userHandle.bytes, Charsets.UTF_8)
        val stored = store.findByCredentialId(credentialId)
        return AssertionResolution(
            sub = sub,
            credentialId = credentialId,
            displayName = stored?.displayName ?: sub,
        )
    }

    data class AssertionResolution(val sub: String, val credentialId: String, val displayName: String)
}

/**
 * Adapter between [PasskeyStore] and Yubico's [CredentialRepository] contract.
 * We use the sub (= username) as both the stored `username` and the WebAuthn
 * `userHandle`, encoded as UTF-8 bytes.
 */
private class PasskeyCredentialRepository(private val store: PasskeyStore) : CredentialRepository {

    override fun getCredentialIdsForUsername(username: String): Set<PublicKeyCredentialDescriptor> =
        store.listBySub(username).map { cred ->
            PublicKeyCredentialDescriptor.builder()
                .id(YubByteArray.fromBase64Url(cred.credentialId))
                .build()
        }.toSet()

    override fun getUserHandleForUsername(username: String): Optional<YubByteArray> =
        Optional.of(YubByteArray(username.toByteArray(Charsets.UTF_8)))

    override fun getUsernameForUserHandle(userHandle: YubByteArray): Optional<String> =
        Optional.of(String(userHandle.bytes, Charsets.UTF_8))

    override fun lookup(credentialId: YubByteArray, userHandle: YubByteArray): Optional<RegisteredCredential> {
        val cred = store.findByCredentialId(credentialId.base64Url) ?: return Optional.empty()
        return Optional.of(
            RegisteredCredential.builder()
                .credentialId(credentialId)
                .userHandle(userHandle)
                .publicKeyCose(YubByteArray.fromBase64Url(cred.publicKeyCose))
                .signatureCount(cred.signatureCount)
                .build()
        )
    }

    override fun lookupAll(credentialId: YubByteArray): Set<RegisteredCredential> {
        val cred = store.findByCredentialId(credentialId.base64Url) ?: return emptySet()
        return setOf(
            RegisteredCredential.builder()
                .credentialId(credentialId)
                .userHandle(YubByteArray(cred.sub.toByteArray(Charsets.UTF_8)))
                .publicKeyCose(YubByteArray.fromBase64Url(cred.publicKeyCose))
                .signatureCount(cred.signatureCount)
                .build()
        )
    }
}
