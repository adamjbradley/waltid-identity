@file:OptIn(ExperimentalTime::class)

package id.walt.webwallet.service.account


import id.walt.webwallet.db.models.Accounts
import id.walt.webwallet.db.models.OidcLogins
import id.walt.webwallet.utils.JwkUtils.verifyToken
import id.walt.webwallet.web.model.OidcAccountRequest
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.toJavaInstant
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@OptIn(ExperimentalUuidApi::class)
object OidcAccountStrategy : PasswordlessAccountStrategy<OidcAccountRequest>() {
    override suspend fun register(tenant: String, request: OidcAccountRequest): Result<RegistrationResult> {
        val jwt = verifyToken(request.token)

        require(!AccountsService.hasAccountOidcId(jwt.subject)) { "Account already exists with OIDC id: ${request.token}" }

        val createdAccountId = transaction {
            val accountId = Accounts.insert {
                it[Accounts.tenant] = tenant
                it[id] = Uuid.random()
                it[name] = jwt.getClaim("name").asString()
                it[email] = jwt.getClaim("email").asString()
                it[createdOn] = Clock.System.now().toJavaInstant()
            }[Accounts.id]

            OidcLogins.insert {
                it[OidcLogins.tenant] = tenant
                it[OidcLogins.accountId] = accountId
                it[oidcId] = jwt.subject
            }

            accountId
        }

        return Result.success(RegistrationResult(createdAccountId))
    }


    override suspend fun authenticate(tenant: String, request: OidcAccountRequest): AuthenticatedUser {
        val jwt = verifyToken(request.token)

        val registeredUserId = if (AccountsService.hasAccountOidcId(jwt.subject)) {
            AccountsService.getAccountByOidcId(jwt.subject)!!.id
        } else {
            // Check if an account with the same email already exists (e.g. from email/password signup)
            val email = jwt.getClaim("email")?.asString()
            val existingAccount = email?.let {
                transaction {
                    Accounts.selectAll()
                        .where { (Accounts.tenant eq tenant) and (Accounts.email eq it) }
                        .firstOrNull()
                        ?.let { row -> row[Accounts.id] }
                }
            }
            if (existingAccount != null) {
                // Link existing account to this OIDC subject
                transaction {
                    OidcLogins.insert {
                        it[OidcLogins.tenant] = tenant
                        it[OidcLogins.accountId] = existingAccount
                        it[oidcId] = jwt.subject
                    }
                }
                existingAccount
            } else {
                AccountsService.register(tenant, request).getOrThrow().id
            }
        }
        // TODO: change id to wallet-id (also in the frontend)
        return UsernameAuthenticatedUser(registeredUserId, jwt.subject)
    }
}
