package id.walt.etsi.tsl

import id.walt.etsi.tsl.models.*
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.datetime.Instant
import javax.xml.stream.XMLInputFactory
import javax.xml.stream.XMLStreamConstants
import javax.xml.stream.XMLStreamReader

private val log = KotlinLogging.logger {}

actual object TslParser {
    actual fun parse(xmlContent: String): TrustServiceList {
        val factory = XMLInputFactory.newInstance()
        // Security: disable external entities
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false)
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false)
        val reader = factory.createXMLStreamReader(xmlContent.reader())

        return parseTrustServiceList(reader)
    }

    private fun parseTrustServiceList(reader: XMLStreamReader): TrustServiceList {
        var schemeTerritory = ""
        var schemeOperatorName = ""
        var listIssueDate: Instant? = null
        var nextUpdate: Instant? = null
        var sequenceNumber: Int? = null
        val providers = mutableListOf<TrustServiceProvider>()
        val pointers = mutableListOf<TslPointer>()
        var currentElement = ""
        var inSchemeInformation = false
        var inPointersToOtherTSL = false

        while (reader.hasNext()) {
            when (reader.next()) {
                XMLStreamConstants.START_ELEMENT -> {
                    val localName = reader.localName
                    currentElement = localName

                    when (localName) {
                        "SchemeInformation" -> inSchemeInformation = true
                        "PointersToOtherTSL" -> inPointersToOtherTSL = true
                        "OtherTSLPointer" -> if (inPointersToOtherTSL) {
                            pointers.add(parsePointer(reader))
                        }
                        "TrustServiceProvider" -> {
                            providers.add(parseTrustServiceProvider(reader))
                        }
                    }
                }
                XMLStreamConstants.CHARACTERS -> {
                    val text = reader.text.trim()
                    if (text.isNotEmpty() && inSchemeInformation) {
                        when (currentElement) {
                            "SchemeTerritory" -> schemeTerritory = text
                            "SchemeOperatorName", "Name" -> {
                                if (schemeOperatorName.isEmpty()) schemeOperatorName = text
                            }
                            "ListIssueDateTime" -> listIssueDate = parseInstantSafe(text)
                            "NextUpdate", "DateTime" -> {
                                if (nextUpdate == null) nextUpdate = parseInstantSafe(text)
                            }
                            "TSLSequenceNumber" -> sequenceNumber = text.toIntOrNull()
                        }
                    }
                }
                XMLStreamConstants.END_ELEMENT -> {
                    when (reader.localName) {
                        "SchemeInformation" -> inSchemeInformation = false
                        "PointersToOtherTSL" -> inPointersToOtherTSL = false
                    }
                }
            }
        }

        return TrustServiceList(
            schemeTerritory = schemeTerritory,
            schemeOperatorName = schemeOperatorName,
            listIssueDate = listIssueDate,
            nextUpdate = nextUpdate,
            trustServiceProviders = providers,
            pointers = pointers,
            sequenceNumber = sequenceNumber
        )
    }

    private fun parsePointer(reader: XMLStreamReader): TslPointer {
        var location = ""
        var territory = ""
        var mimeType: String? = null
        var currentElement = ""

        while (reader.hasNext()) {
            when (reader.next()) {
                XMLStreamConstants.START_ELEMENT -> currentElement = reader.localName
                XMLStreamConstants.CHARACTERS -> {
                    val text = reader.text.trim()
                    if (text.isNotEmpty()) {
                        when (currentElement) {
                            "TSLLocation" -> location = text
                            "SchemeTerritory" -> territory = text
                            "MimeType" -> mimeType = text
                        }
                    }
                }
                XMLStreamConstants.END_ELEMENT -> {
                    if (reader.localName == "OtherTSLPointer") break
                }
            }
        }

        return TslPointer(
            location = location,
            schemeTerritory = territory,
            mimeType = mimeType
        )
    }

    private fun parseTrustServiceProvider(reader: XMLStreamReader): TrustServiceProvider {
        var name = ""
        var tradeName: String? = null
        val services = mutableListOf<TrustServiceEntry>()
        var currentElement = ""
        var inTSPInformation = false

        while (reader.hasNext()) {
            when (reader.next()) {
                XMLStreamConstants.START_ELEMENT -> {
                    currentElement = reader.localName
                    when (reader.localName) {
                        "TSPInformation" -> inTSPInformation = true
                        "TSPService" -> services.add(parseTrustServiceEntry(reader))
                    }
                }
                XMLStreamConstants.CHARACTERS -> {
                    val text = reader.text.trim()
                    if (text.isNotEmpty() && inTSPInformation) {
                        when (currentElement) {
                            "Name" -> if (name.isEmpty()) name = text
                            "TSPTradeName" -> tradeName = text
                        }
                    }
                }
                XMLStreamConstants.END_ELEMENT -> {
                    when (reader.localName) {
                        "TSPInformation" -> inTSPInformation = false
                        "TrustServiceProvider" -> break
                    }
                }
            }
        }

        return TrustServiceProvider(
            name = name,
            tradeName = tradeName,
            trustServices = services
        )
    }

    private fun parseTrustServiceEntry(reader: XMLStreamReader): TrustServiceEntry {
        var serviceType = ""
        var serviceName = ""
        var status = ""
        var statusStartingTime: Instant? = null
        var certificate: String? = null
        var subjectName: String? = null
        var currentElement = ""

        while (reader.hasNext()) {
            when (reader.next()) {
                XMLStreamConstants.START_ELEMENT -> currentElement = reader.localName
                XMLStreamConstants.CHARACTERS -> {
                    val text = reader.text.trim()
                    if (text.isNotEmpty()) {
                        when (currentElement) {
                            "ServiceTypeIdentifier" -> serviceType = text
                            "Name" -> if (serviceName.isEmpty()) serviceName = text
                            "ServiceStatus" -> status = text
                            "StatusStartingTime" -> statusStartingTime = parseInstantSafe(text)
                            "X509Certificate" -> certificate = text
                            "X509SubjectName" -> subjectName = text
                        }
                    }
                }
                XMLStreamConstants.END_ELEMENT -> {
                    if (reader.localName == "TSPService") break
                }
            }
        }

        return TrustServiceEntry(
            serviceType = serviceType,
            serviceName = serviceName,
            currentStatus = status,
            statusStartingTime = statusStartingTime,
            serviceDigitalIdentity = if (certificate != null || subjectName != null) {
                TrustAnchorInfo(x509Certificate = certificate, x509SubjectName = subjectName)
            } else null
        )
    }

    private fun parseInstantSafe(text: String): Instant? {
        return try {
            Instant.parse(text)
        } catch (e: Exception) {
            log.debug { "Could not parse instant: $text" }
            null
        }
    }
}
