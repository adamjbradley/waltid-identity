package id.walt.etsi.tsl.config

import kotlinx.serialization.Serializable

@Serializable
data class TslConfig(
    val lotlUrl: String = "https://ec.europa.eu/tools/lotl/eu-lotl.xml",
    val cacheTtlHours: Int = 24,
    val memberStates: List<String> = listOf("*"),
    val validateSignatures: Boolean = true,
    val localTestFile: String? = null
) {
    val isLocalTestMode: Boolean
        get() = localTestFile != null
}
