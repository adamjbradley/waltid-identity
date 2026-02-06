package id.walt.trust

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class TrustSource {
    @SerialName("etsi_tl")
    ETSI_TL,

    @SerialName("openid_federation")
    OPENID_FEDERATION,

    @SerialName("vical")
    VICAL,

    @SerialName("static_list")
    STATIC_LIST
}
