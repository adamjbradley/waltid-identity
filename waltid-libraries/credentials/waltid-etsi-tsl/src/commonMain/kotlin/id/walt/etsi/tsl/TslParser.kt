package id.walt.etsi.tsl

import id.walt.etsi.tsl.models.TrustServiceList

expect object TslParser {
    fun parse(xmlContent: String): TrustServiceList
}
