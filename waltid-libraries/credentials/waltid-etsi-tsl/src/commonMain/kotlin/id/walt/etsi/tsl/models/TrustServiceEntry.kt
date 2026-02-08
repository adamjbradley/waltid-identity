package id.walt.etsi.tsl.models

import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable

@Serializable
data class TrustServiceEntry(
    val serviceType: String,
    val serviceName: String,
    val currentStatus: String,
    val statusStartingTime: Instant? = null,
    val serviceDigitalIdentity: TrustAnchorInfo? = null
) {
    companion object {
        // ETSI TS 119 612 service type URIs
        const val TYPE_CA_QC = "http://uri.etsi.org/TrstSvc/Svctype/CA/QC"
        const val TYPE_QESVAL = "http://uri.etsi.org/TrstSvc/Svctype/QESVal"
        const val TYPE_TSA = "http://uri.etsi.org/TrstSvc/Svctype/TSA"
        const val TYPE_EDS = "http://uri.etsi.org/TrstSvc/Svctype/EDS/Q"
        const val TYPE_PSES_Q = "http://uri.etsi.org/TrstSvc/Svctype/PSES/Q"
        const val TYPE_REM_Q = "http://uri.etsi.org/TrstSvc/Svctype/REM/Q"

        // ETSI status URIs
        const val STATUS_GRANTED = "http://uri.etsi.org/TrstSvc/TrustedList/Svcstatus/granted"
        const val STATUS_WITHDRAWN = "http://uri.etsi.org/TrstSvc/TrustedList/Svcstatus/withdrawn"
        const val STATUS_DEPRECATED = "http://uri.etsi.org/TrstSvc/TrustedList/Svcstatus/deprecatedatnationallevel"
        const val STATUS_RECOGNISED = "http://uri.etsi.org/TrstSvc/TrustedList/Svcstatus/recognisedatnationallevel"
    }

    val isGranted: Boolean
        get() = currentStatus == STATUS_GRANTED

    val isQualified: Boolean
        get() = serviceType.contains("/Q") || serviceType == TYPE_CA_QC
}
