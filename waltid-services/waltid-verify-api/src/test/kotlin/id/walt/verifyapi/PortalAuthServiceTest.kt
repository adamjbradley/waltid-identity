package id.walt.verifyapi

import id.walt.verifyapi.portal.BCryptUtil
import id.walt.verifyapi.portal.PortalAuthService
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PortalAuthServiceTest {

    @Test
    fun `test password hash is consistent for verification`() {
        val password = "securePassword123!"
        val hash = BCryptUtil.hashPassword(password)
        assertTrue(BCryptUtil.checkPassword(password, hash), "Password should verify against its hash")
    }

    @Test
    fun `test different passwords produce different hashes`() {
        val hash1 = BCryptUtil.hashPassword("password1")
        val hash2 = BCryptUtil.hashPassword("password2")
        assertNotEquals(hash1, hash2, "Different passwords should produce different hashes")
    }

    @Test
    fun `test same password produces different hashes due to salt`() {
        val password = "samePassword"
        val hash1 = BCryptUtil.hashPassword(password)
        val hash2 = BCryptUtil.hashPassword(password)
        assertNotEquals(hash1, hash2, "Same password should produce different hashes due to random salt")
        assertTrue(BCryptUtil.checkPassword(password, hash1))
        assertTrue(BCryptUtil.checkPassword(password, hash2))
    }

    @Test
    fun `test wrong password does not verify`() {
        val hash = BCryptUtil.hashPassword("correctPassword")
        assertFalse(BCryptUtil.checkPassword("wrongPassword", hash), "Wrong password should not verify")
    }

    @Test
    fun `test empty password can be hashed and verified`() {
        val hash = BCryptUtil.hashPassword("")
        assertTrue(BCryptUtil.checkPassword("", hash), "Empty password should verify")
        assertFalse(BCryptUtil.checkPassword("notEmpty", hash), "Non-empty should not verify against empty hash")
    }

    @Test
    fun `test password hashing is case sensitive`() {
        val hash = BCryptUtil.hashPassword("Password")
        assertTrue(BCryptUtil.checkPassword("Password", hash))
        assertFalse(BCryptUtil.checkPassword("password", hash), "Password verification should be case sensitive")
        assertFalse(BCryptUtil.checkPassword("PASSWORD", hash), "Password verification should be case sensitive")
    }

    @Test
    fun `test password with special characters`() {
        val password = "P@\$\$w0rd!#%^&*()_+-=[]{}|;':\",./<>?"
        val hash = BCryptUtil.hashPassword(password)
        assertTrue(BCryptUtil.checkPassword(password, hash), "Special characters should be handled correctly")
    }

    @Test
    fun `test hash format is valid`() {
        val hash = BCryptUtil.hashPassword("test")
        assertTrue(hash.startsWith("\$pbkdf2\$"), "Hash should start with \$pbkdf2\$")
        val parts = hash.split("\$").filter { it.isNotEmpty() }
        assertEquals(4, parts.size, "Hash should have 4 parts: algorithm, iterations, salt, hash")
        assertEquals("pbkdf2", parts[0])
        assertTrue(parts[1].toIntOrNull() != null, "Second part should be iterations count")
    }

    @Test
    fun `test JWT token generation and validation`() {
        val userInfo = PortalAuthService.UserInfo(
            userId = java.util.UUID.randomUUID(),
            email = "test@example.com",
            passwordHash = "",
            organizationId = java.util.UUID.randomUUID(),
            organizationName = "Test Org",
            role = "admin",
            emailVerifiedAt = java.time.Instant.now()
        )

        val tokens = PortalAuthService.generateTokens(userInfo)

        assertTrue(tokens.accessToken.isNotBlank(), "Access token should not be blank")
        assertTrue(tokens.refreshToken.isNotBlank(), "Refresh token should not be blank")
        assertNotEquals(tokens.accessToken, tokens.refreshToken, "Access and refresh tokens should be different")
        assertTrue(tokens.accessTokenExpiresAt > 0, "Access token expiry should be set")
        assertTrue(tokens.refreshTokenExpiresAt > 0, "Refresh token expiry should be set")
        assertTrue(
            tokens.refreshTokenExpiresAt > tokens.accessTokenExpiresAt,
            "Refresh token should expire after access token"
        )
        assertEquals("Bearer", tokens.tokenType)
    }

    @Test
    fun `test access token validation returns principal`() {
        val userInfo = PortalAuthService.UserInfo(
            userId = java.util.UUID.randomUUID(),
            email = "portal@example.com",
            passwordHash = "",
            organizationId = java.util.UUID.randomUUID(),
            organizationName = "Portal Test Org",
            role = "viewer",
            emailVerifiedAt = null
        )

        val tokens = PortalAuthService.generateTokens(userInfo)
        val principal = PortalAuthService.validateAccessToken(tokens.accessToken)

        assertNotNull(principal, "Valid access token should return a principal")
        assertEquals(userInfo.userId, principal.userId)
        assertEquals(userInfo.email, principal.email)
        assertEquals(userInfo.organizationId, principal.organizationId)
        assertEquals(userInfo.organizationName, principal.organizationName)
        assertEquals(userInfo.role, principal.role)
        assertEquals("access", principal.tokenType)
    }

    @Test
    fun `test refresh token is not valid as access token`() {
        val userInfo = PortalAuthService.UserInfo(
            userId = java.util.UUID.randomUUID(),
            email = "test@example.com",
            passwordHash = "",
            organizationId = java.util.UUID.randomUUID(),
            organizationName = "Test Org",
            role = "admin",
            emailVerifiedAt = null
        )

        val tokens = PortalAuthService.generateTokens(userInfo)
        val principal = PortalAuthService.validateAccessToken(tokens.refreshToken)
        assertNull(principal, "Refresh token should not be valid as access token")
    }

    @Test
    fun `test invalid token returns null`() {
        val principal = PortalAuthService.validateAccessToken("invalid.token.here")
        assertNull(principal, "Invalid token should return null")
    }

    @Test
    fun `test empty token returns null`() {
        val principal = PortalAuthService.validateAccessToken("")
        assertNull(principal, "Empty token should return null")
    }

    @Test
    fun `test tampered token returns null`() {
        val userInfo = PortalAuthService.UserInfo(
            userId = java.util.UUID.randomUUID(),
            email = "test@example.com",
            passwordHash = "",
            organizationId = java.util.UUID.randomUUID(),
            organizationName = "Test Org",
            role = "admin",
            emailVerifiedAt = null
        )

        val tokens = PortalAuthService.generateTokens(userInfo)
        val tampered = tokens.accessToken.dropLast(5) + "XXXXX"
        val principal = PortalAuthService.validateAccessToken(tampered)
        assertNull(principal, "Tampered token should return null")
    }

    @Test
    fun `test token contains expected claims`() {
        val userId = java.util.UUID.randomUUID()
        val orgId = java.util.UUID.randomUUID()
        val userInfo = PortalAuthService.UserInfo(
            userId = userId,
            email = "claims@test.com",
            passwordHash = "",
            organizationId = orgId,
            organizationName = "Claims Test",
            role = "admin",
            emailVerifiedAt = java.time.Instant.now()
        )

        val tokens = PortalAuthService.generateTokens(userInfo)
        val decoded = com.auth0.jwt.JWT.decode(tokens.accessToken)

        assertEquals(userId.toString(), decoded.subject)
        assertEquals("claims@test.com", decoded.getClaim("email").asString())
        assertEquals(orgId.toString(), decoded.getClaim("org_id").asString())
        assertEquals("Claims Test", decoded.getClaim("org_name").asString())
        assertEquals("admin", decoded.getClaim("role").asString())
        assertEquals("access", decoded.getClaim("type").asString())
        assertEquals(PortalAuthService.jwtConfig.issuer, decoded.issuer)
        assertEquals(PortalAuthService.jwtConfig.audience, decoded.audience[0])
    }

    @Test
    fun `test hashPassword and verifyPassword integration`() {
        val password = "MySecretPassword123!"
        val hash = PortalAuthService.hashPassword(password)
        assertTrue(PortalAuthService.verifyPassword(password, hash))
        assertFalse(PortalAuthService.verifyPassword("WrongPassword", hash))
    }

    @Test
    fun `test JWT config has valid defaults`() {
        val config = PortalAuthService.jwtConfig

        assertTrue(config.issuer.isNotBlank(), "Issuer should not be blank")
        assertTrue(config.audience.isNotBlank(), "Audience should not be blank")
        assertTrue(config.secret.isNotBlank(), "Secret should not be blank")
        assertTrue(config.realm.isNotBlank(), "Realm should not be blank")
        assertTrue(config.accessTokenLifetime.isPositive(), "Access token lifetime should be positive")
        assertTrue(config.refreshTokenLifetime.isPositive(), "Refresh token lifetime should be positive")
        assertTrue(
            config.refreshTokenLifetime > config.accessTokenLifetime,
            "Refresh token should live longer than access token"
        )
    }
}
