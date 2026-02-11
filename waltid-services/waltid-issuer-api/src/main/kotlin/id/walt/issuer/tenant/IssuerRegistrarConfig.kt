package id.walt.issuer.tenant

import id.walt.commons.config.WaltConfig
import kotlinx.serialization.Serializable

@Serializable
data class IssuerRegistrarConfig(
    val storageDir: String = "config/issuer-tenants"
) : WaltConfig()
