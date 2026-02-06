package id.walt.etsi.tsl

import id.walt.etsi.tsl.models.*
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.datetime.Instant

private val log = KotlinLogging.logger {}

actual object TslParser {
    actual fun parse(xmlContent: String): TrustServiceList {
        val parser = js("new DOMParser()")
        val doc = parser.parseFromString(xmlContent, "text/xml")

        val schemeTerritory = getTextContent(doc, "SchemeTerritory") ?: ""
        val schemeOperatorName = getTextContent(doc, "SchemeOperatorName") ?: ""
        val listIssueDate = getTextContent(doc, "ListIssueDateTime")?.let { parseInstantSafe(it) }

        val pointers = parsePointers(doc)
        val providers = parseProviders(doc)

        return TrustServiceList(
            schemeTerritory = schemeTerritory,
            schemeOperatorName = schemeOperatorName,
            listIssueDate = listIssueDate,
            trustServiceProviders = providers,
            pointers = pointers
        )
    }

    private fun getTextContent(doc: dynamic, tagName: String): String? {
        val elements = doc.getElementsByTagName(tagName)
        return if (elements.length > 0) {
            elements.item(0).textContent as? String
        } else null
    }

    private fun parsePointers(doc: dynamic): List<TslPointer> {
        val pointerElements = doc.getElementsByTagName("OtherTSLPointer")
        val pointers = mutableListOf<TslPointer>()
        for (i in 0 until (pointerElements.length as Int)) {
            val elem = pointerElements.item(i)
            val location = getTextContent(elem, "TSLLocation") ?: continue
            val territory = getTextContent(elem, "SchemeTerritory") ?: ""
            pointers.add(TslPointer(location = location, schemeTerritory = territory))
        }
        return pointers
    }

    private fun parseProviders(doc: dynamic): List<TrustServiceProvider> {
        val providerElements = doc.getElementsByTagName("TrustServiceProvider")
        val providers = mutableListOf<TrustServiceProvider>()
        for (i in 0 until (providerElements.length as Int)) {
            val elem = providerElements.item(i)
            val name = getTextContent(elem, "TSPName") ?: getTextContent(elem, "Name") ?: ""
            val services = parseServices(elem)
            providers.add(TrustServiceProvider(name = name, trustServices = services))
        }
        return providers
    }

    private fun parseServices(providerElem: dynamic): List<TrustServiceEntry> {
        val serviceElements = providerElem.getElementsByTagName("TSPService")
        val services = mutableListOf<TrustServiceEntry>()
        for (i in 0 until (serviceElements.length as Int)) {
            val elem = serviceElements.item(i)
            val serviceType = getTextContent(elem, "ServiceTypeIdentifier") ?: continue
            val serviceName = getTextContent(elem, "ServiceName") ?: getTextContent(elem, "Name") ?: ""
            val status = getTextContent(elem, "ServiceStatus") ?: ""
            services.add(
                TrustServiceEntry(
                    serviceType = serviceType,
                    serviceName = serviceName,
                    currentStatus = status
                )
            )
        }
        return services
    }

    private fun parseInstantSafe(text: String): Instant? {
        return try { Instant.parse(text) } catch (e: Exception) {
            log.debug { "Could not parse instant: $text" }
            null
        }
    }
}
