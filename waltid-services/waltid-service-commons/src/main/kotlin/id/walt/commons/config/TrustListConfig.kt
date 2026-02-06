package id.walt.commons.config

data class TrustListConfig(
    val enabled: Boolean = false,
    val etsi: EtsiConfig = EtsiConfig(),
    val openidFederation: OpenIdFederationConfig = OpenIdFederationConfig()
) {
    data class EtsiConfig(
        val lotlUrl: String = "https://ec.europa.eu/tools/lotl/eu-lotl.xml",
        val cacheTtlHours: Int = 24,
        val memberStates: List<String> = listOf("*"),
        val validateSignatures: Boolean = true
    )

    data class OpenIdFederationConfig(
        val trustAnchors: List<String> = emptyList()
    )
}
