package id.walt.verifyapi.portal

import id.walt.verifyapi.db.VerifyOrganizations
import id.walt.verifyapi.db.VerifyUsers
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.time.Instant
import java.util.UUID

private val logger = KotlinLogging.logger {}

// ============================================================
// Request DTOs
// ============================================================

/**
 * Request DTO for user signup.
 * Creates both an organization and the first admin user.
 */
@Serializable
data class SignupRequest(
    val email: String,
    val password: String,
    @SerialName("organization_name")
    val organizationName: String
)

/**
 * Request DTO for user login.
 */
@Serializable
data class LoginRequest(
    val email: String,
    val password: String
)

/**
 * Request DTO for token refresh.
 */
@Serializable
data class RefreshRequest(
    @SerialName("refresh_token")
    val refreshToken: String
)

/**
 * Request DTO for password reset initiation.
 */
@Serializable
data class PasswordResetRequest(
    val email: String
)

/**
 * Request DTO for password reset confirmation.
 */
@Serializable
data class PasswordResetConfirmRequest(
    val token: String,
    @SerialName("new_password")
    val newPassword: String
)

// ============================================================
// Response DTOs
// ============================================================

/**
 * Response DTO for successful authentication (signup/login/refresh).
 */
@Serializable
data class AuthResponse(
    @SerialName("access_token")
    val accessToken: String,
    @SerialName("refresh_token")
    val refreshToken: String,
    @SerialName("token_type")
    val tokenType: String = "Bearer",
    @SerialName("expires_in")
    val expiresIn: Long,
    val user: UserResponse
)

/**
 * Response DTO for user information.
 */
@Serializable
data class UserResponse(
    val id: String,
    val email: String,
    val role: String,
    val organization: OrganizationResponse
)

/**
 * Response DTO for organization information.
 */
@Serializable
data class OrganizationResponse(
    val id: String,
    val name: String
)

/**
 * Simple error response.
 */
@Serializable
data class AuthErrorResponse(
    val error: String,
    val message: String
)

/**
 * Simple success response for operations that don't return data.
 */
@Serializable
data class SuccessResponse(
    val message: String
)

// ============================================================
// Validation
// ============================================================

private const val MIN_PASSWORD_LENGTH = 8
private const val MAX_EMAIL_LENGTH = 255
private const val MAX_ORG_NAME_LENGTH = 255

private val EMAIL_REGEX = Regex("^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$")

sealed class ValidationResult {
    data object Valid : ValidationResult()
    data class Invalid(val field: String, val message: String) : ValidationResult()
}

private fun validateEmail(email: String): ValidationResult {
    if (email.isBlank()) {
        return ValidationResult.Invalid("email", "Email is required")
    }
    if (email.length > MAX_EMAIL_LENGTH) {
        return ValidationResult.Invalid("email", "Email must be at most $MAX_EMAIL_LENGTH characters")
    }
    if (!EMAIL_REGEX.matches(email)) {
        return ValidationResult.Invalid("email", "Invalid email format")
    }
    return ValidationResult.Valid
}

private fun validatePassword(password: String): ValidationResult {
    if (password.isBlank()) {
        return ValidationResult.Invalid("password", "Password is required")
    }
    if (password.length < MIN_PASSWORD_LENGTH) {
        return ValidationResult.Invalid("password", "Password must be at least $MIN_PASSWORD_LENGTH characters")
    }
    return ValidationResult.Valid
}

private fun validateOrganizationName(name: String): ValidationResult {
    if (name.isBlank()) {
        return ValidationResult.Invalid("organization_name", "Organization name is required")
    }
    if (name.length > MAX_ORG_NAME_LENGTH) {
        return ValidationResult.Invalid("organization_name", "Organization name must be at most $MAX_ORG_NAME_LENGTH characters")
    }
    return ValidationResult.Valid
}

// ============================================================
// Routes
// ============================================================

/**
 * Portal authentication routes.
 *
 * These endpoints handle user authentication for the Merchant Self-Service Portal.
 * They are public endpoints (no API key required) since users don't have API keys
 * until they're authenticated.
 *
 * POST /portal/auth/signup - Create a new organization and admin user
 * POST /portal/auth/login - Authenticate and get tokens
 * POST /portal/auth/refresh - Refresh access token
 * POST /portal/auth/password-reset - Initiate password reset
 * POST /portal/auth/password-reset/confirm - Confirm password reset with token
 */
fun Route.portalAuthRoutes() {
    route("/portal/auth") {
        /**
         * POST /portal/auth/signup
         *
         * Creates a new organization and the first admin user.
         * Returns JWT tokens upon successful registration.
         *
         * Request body:
         * - email: User's email address
         * - password: Password (minimum 8 characters)
         * - organization_name: Name for the new organization
         *
         * Response:
         * - 201 Created: Returns tokens and user info
         * - 400 Bad Request: Validation error
         * - 409 Conflict: Email already exists
         */
        post("/signup") {
            val request = try {
                call.receive<SignupRequest>()
            } catch (e: Exception) {
                logger.debug { "Failed to parse signup request: ${e.message}" }
                call.respond(
                    HttpStatusCode.BadRequest,
                    AuthErrorResponse("invalid_request", "Invalid request body")
                )
                return@post
            }

            // Validate email
            when (val result = validateEmail(request.email)) {
                is ValidationResult.Invalid -> {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        AuthErrorResponse("validation_error", result.message)
                    )
                    return@post
                }
                ValidationResult.Valid -> {}
            }

            // Validate password
            when (val result = validatePassword(request.password)) {
                is ValidationResult.Invalid -> {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        AuthErrorResponse("validation_error", result.message)
                    )
                    return@post
                }
                ValidationResult.Valid -> {}
            }

            // Validate organization name
            when (val result = validateOrganizationName(request.organizationName)) {
                is ValidationResult.Invalid -> {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        AuthErrorResponse("validation_error", result.message)
                    )
                    return@post
                }
                ValidationResult.Valid -> {}
            }

            val normalizedEmail = request.email.lowercase().trim()

            // Check if email already exists
            val emailExists = transaction {
                VerifyUsers.selectAll()
                    .where { VerifyUsers.email eq normalizedEmail }
                    .count() > 0
            }

            if (emailExists) {
                call.respond(
                    HttpStatusCode.Conflict,
                    AuthErrorResponse("email_exists", "An account with this email already exists")
                )
                return@post
            }

            // Create organization and user
            val now = Instant.now()
            val passwordHash = PortalAuthService.hashPassword(request.password)

            val (orgId, userId) = transaction {
                // Create organization
                val orgId = VerifyOrganizations.insert {
                    it[name] = request.organizationName.trim()
                    it[billingEmail] = normalizedEmail
                    it[plan] = "free"
                    it[createdAt] = now
                    it[updatedAt] = now
                } get VerifyOrganizations.id

                // Create admin user
                val userId = VerifyUsers.insert {
                    it[email] = normalizedEmail
                    it[VerifyUsers.passwordHash] = passwordHash
                    it[organizationId] = orgId
                    it[role] = "admin"
                    it[emailVerifiedAt] = null  // Email not yet verified
                    it[createdAt] = now
                    it[lastLoginAt] = now
                } get VerifyUsers.id

                orgId.value to userId.value
            }

            logger.info { "Created new organization $orgId with admin user $userId (email: $normalizedEmail)" }

            // Generate tokens
            val userInfo = PortalAuthService.UserInfo(
                userId = userId,
                email = normalizedEmail,
                passwordHash = passwordHash,
                organizationId = orgId,
                organizationName = request.organizationName.trim(),
                role = "admin",
                emailVerifiedAt = null
            )

            val tokens = PortalAuthService.generateTokens(userInfo)

            val response = AuthResponse(
                accessToken = tokens.accessToken,
                refreshToken = tokens.refreshToken,
                tokenType = tokens.tokenType,
                expiresIn = tokens.accessTokenExpiresAt - Instant.now().epochSecond,
                user = UserResponse(
                    id = userId.toString(),
                    email = normalizedEmail,
                    role = "admin",
                    organization = OrganizationResponse(
                        id = orgId.toString(),
                        name = request.organizationName.trim()
                    )
                )
            )

            call.respond(HttpStatusCode.Created, response)
        }

        /**
         * POST /portal/auth/login
         *
         * Authenticates a user and returns JWT tokens.
         *
         * Request body:
         * - email: User's email address
         * - password: User's password
         *
         * Response:
         * - 200 OK: Returns tokens and user info
         * - 400 Bad Request: Validation error
         * - 401 Unauthorized: Invalid credentials
         */
        post("/login") {
            val request = try {
                call.receive<LoginRequest>()
            } catch (e: Exception) {
                logger.debug { "Failed to parse login request: ${e.message}" }
                call.respond(
                    HttpStatusCode.BadRequest,
                    AuthErrorResponse("invalid_request", "Invalid request body")
                )
                return@post
            }

            // Validate email format
            when (val result = validateEmail(request.email)) {
                is ValidationResult.Invalid -> {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        AuthErrorResponse("validation_error", result.message)
                    )
                    return@post
                }
                ValidationResult.Valid -> {}
            }

            // Validate password is not empty
            if (request.password.isBlank()) {
                call.respond(
                    HttpStatusCode.BadRequest,
                    AuthErrorResponse("validation_error", "Password is required")
                )
                return@post
            }

            // Authenticate user
            val (authResult, userInfo) = PortalAuthService.authenticateUser(
                request.email,
                request.password
            )

            when (authResult) {
                is PasswordVerifyResult.UserNotFound,
                is PasswordVerifyResult.Invalid -> {
                    // Use same message for both to prevent email enumeration
                    call.respond(
                        HttpStatusCode.Unauthorized,
                        AuthErrorResponse("invalid_credentials", "Invalid email or password")
                    )
                    return@post
                }
                is PasswordVerifyResult.EmailNotVerified -> {
                    call.respond(
                        HttpStatusCode.Unauthorized,
                        AuthErrorResponse("email_not_verified", "Please verify your email address")
                    )
                    return@post
                }
                PasswordVerifyResult.Valid -> {}
            }

            // Generate tokens
            val tokens = PortalAuthService.generateTokens(userInfo!!)

            val response = AuthResponse(
                accessToken = tokens.accessToken,
                refreshToken = tokens.refreshToken,
                tokenType = tokens.tokenType,
                expiresIn = tokens.accessTokenExpiresAt - Instant.now().epochSecond,
                user = UserResponse(
                    id = userInfo.userId.toString(),
                    email = userInfo.email,
                    role = userInfo.role,
                    organization = OrganizationResponse(
                        id = userInfo.organizationId.toString(),
                        name = userInfo.organizationName
                    )
                )
            )

            call.respond(HttpStatusCode.OK, response)
        }

        /**
         * POST /portal/auth/refresh
         *
         * Exchanges a refresh token for a new token pair.
         *
         * Request body:
         * - refresh_token: The refresh token from a previous login/signup/refresh
         *
         * Response:
         * - 200 OK: Returns new tokens and user info
         * - 400 Bad Request: Missing refresh token
         * - 401 Unauthorized: Invalid or expired refresh token
         */
        post("/refresh") {
            val request = try {
                call.receive<RefreshRequest>()
            } catch (e: Exception) {
                logger.debug { "Failed to parse refresh request: ${e.message}" }
                call.respond(
                    HttpStatusCode.BadRequest,
                    AuthErrorResponse("invalid_request", "Invalid request body")
                )
                return@post
            }

            if (request.refreshToken.isBlank()) {
                call.respond(
                    HttpStatusCode.BadRequest,
                    AuthErrorResponse("validation_error", "Refresh token is required")
                )
                return@post
            }

            // Refresh tokens
            when (val result = PortalAuthService.refreshTokens(request.refreshToken)) {
                is RefreshResult.InvalidToken -> {
                    call.respond(
                        HttpStatusCode.Unauthorized,
                        AuthErrorResponse("invalid_token", "Invalid or expired refresh token")
                    )
                    return@post
                }
                is RefreshResult.UserNotFound -> {
                    call.respond(
                        HttpStatusCode.Unauthorized,
                        AuthErrorResponse("user_not_found", "User account no longer exists")
                    )
                    return@post
                }
                is RefreshResult.Success -> {
                    val tokens = result.tokens

                    // Get user info for response
                    val userInfo = getUserInfoFromAccessToken(tokens.accessToken)

                    if (userInfo == null) {
                        // This shouldn't happen, but handle gracefully
                        call.respond(
                            HttpStatusCode.InternalServerError,
                            AuthErrorResponse("internal_error", "Failed to retrieve user information")
                        )
                        return@post
                    }

                    val response = AuthResponse(
                        accessToken = tokens.accessToken,
                        refreshToken = tokens.refreshToken,
                        tokenType = tokens.tokenType,
                        expiresIn = tokens.accessTokenExpiresAt - Instant.now().epochSecond,
                        user = UserResponse(
                            id = userInfo.userId.toString(),
                            email = userInfo.email,
                            role = userInfo.role,
                            organization = OrganizationResponse(
                                id = userInfo.organizationId.toString(),
                                name = userInfo.organizationName
                            )
                        )
                    )

                    call.respond(HttpStatusCode.OK, response)
                }
            }
        }

        /**
         * POST /portal/auth/password-reset
         *
         * Initiates a password reset flow by sending a reset email.
         *
         * NOTE: Currently stubbed. In production, this would:
         * 1. Generate a secure reset token
         * 2. Store the token with an expiry
         * 3. Send an email with a reset link
         *
         * Request body:
         * - email: User's email address
         *
         * Response:
         * - 200 OK: Always returns success to prevent email enumeration
         */
        post("/password-reset") {
            val request = try {
                call.receive<PasswordResetRequest>()
            } catch (e: Exception) {
                logger.debug { "Failed to parse password reset request: ${e.message}" }
                call.respond(
                    HttpStatusCode.BadRequest,
                    AuthErrorResponse("invalid_request", "Invalid request body")
                )
                return@post
            }

            // Validate email format
            when (val result = validateEmail(request.email)) {
                is ValidationResult.Invalid -> {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        AuthErrorResponse("validation_error", result.message)
                    )
                    return@post
                }
                ValidationResult.Valid -> {}
            }

            // Always return success to prevent email enumeration
            // In production, this would send an email if the user exists
            logger.info { "Password reset requested for email: ${request.email.lowercase()}" }

            // TODO: Implement actual password reset:
            // 1. Check if user exists
            // 2. Generate secure token (store in verify_password_reset_tokens table)
            // 3. Send email with reset link

            call.respond(
                HttpStatusCode.OK,
                SuccessResponse("If an account exists with this email, a password reset link has been sent")
            )
        }

        /**
         * POST /portal/auth/password-reset/confirm
         *
         * Confirms a password reset with the token from the reset email.
         *
         * NOTE: Currently stubbed. In production, this would:
         * 1. Validate the reset token
         * 2. Update the user's password
         * 3. Invalidate all existing sessions
         *
         * Request body:
         * - token: The reset token from the email
         * - new_password: The new password
         *
         * Response:
         * - 200 OK: Password reset successfully
         * - 400 Bad Request: Invalid token or validation error
         */
        post("/password-reset/confirm") {
            val request = try {
                call.receive<PasswordResetConfirmRequest>()
            } catch (e: Exception) {
                logger.debug { "Failed to parse password reset confirm request: ${e.message}" }
                call.respond(
                    HttpStatusCode.BadRequest,
                    AuthErrorResponse("invalid_request", "Invalid request body")
                )
                return@post
            }

            // Validate token
            if (request.token.isBlank()) {
                call.respond(
                    HttpStatusCode.BadRequest,
                    AuthErrorResponse("validation_error", "Reset token is required")
                )
                return@post
            }

            // Validate new password
            when (val result = validatePassword(request.newPassword)) {
                is ValidationResult.Invalid -> {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        AuthErrorResponse("validation_error", result.message)
                    )
                    return@post
                }
                ValidationResult.Valid -> {}
            }

            // TODO: Implement actual password reset confirmation:
            // 1. Look up token in verify_password_reset_tokens table
            // 2. Verify token hasn't expired
            // 3. Update user's password
            // 4. Delete the token
            // 5. Optionally invalidate all refresh tokens

            // For now, always return error since we haven't implemented token storage
            call.respond(
                HttpStatusCode.BadRequest,
                AuthErrorResponse("invalid_token", "Invalid or expired reset token")
            )
        }
    }
}

/**
 * Helper function to extract user info from a validated access token.
 */
private fun getUserInfoFromAccessToken(accessToken: String): PortalUserPrincipal? {
    return PortalAuthService.validateAccessToken(accessToken)
}
