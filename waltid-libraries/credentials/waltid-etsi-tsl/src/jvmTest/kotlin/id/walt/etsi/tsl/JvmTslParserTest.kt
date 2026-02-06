package id.walt.etsi.tsl

import id.walt.etsi.tsl.models.TrustServiceEntry
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class JvmTslParserTest {

    private lateinit var xmlContent: String

    @BeforeAll
    fun loadTestXml() {
        xmlContent = javaClass.getResourceAsStream("/test-tsl.xml")!!
            .bufferedReader()
            .readText()
    }

    @Test
    fun `test parse extracts scheme territory`() {
        val tsl = TslParser.parse(xmlContent)
        assertEquals("DE", tsl.schemeTerritory)
    }

    @Test
    fun `test parse extracts scheme operator name`() {
        val tsl = TslParser.parse(xmlContent)
        assertEquals("Test Scheme Operator", tsl.schemeOperatorName)
    }

    @Test
    fun `test parse extracts sequence number`() {
        val tsl = TslParser.parse(xmlContent)
        assertEquals(1, tsl.sequenceNumber)
    }

    @Test
    fun `test parse extracts list issue date`() {
        val tsl = TslParser.parse(xmlContent)
        assertNotNull(tsl.listIssueDate)
        assertEquals(Instant.parse("2024-01-15T00:00:00Z"), tsl.listIssueDate)
    }

    @Test
    fun `test parse extracts next update`() {
        val tsl = TslParser.parse(xmlContent)
        assertNotNull(tsl.nextUpdate)
        assertEquals(Instant.parse("2025-06-15T00:00:00Z"), tsl.nextUpdate)
    }

    @Test
    fun `test parse extracts trust service providers`() {
        val tsl = TslParser.parse(xmlContent)
        assertEquals(1, tsl.trustServiceProviders.size)
        assertEquals("Test Trust Service Provider", tsl.trustServiceProviders[0].name)
    }

    @Test
    fun `test parse does not extract trade name from nested Name element`() {
        // The parser sets tradeName when currentElement == "TSPTradeName" but in standard
        // ETSI XML the trade name text is inside a child <Name> element, so currentElement
        // becomes "Name" and the trade name path is not triggered.
        val tsl = TslParser.parse(xmlContent)
        val provider = tsl.trustServiceProviders[0]
        assertNull(provider.tradeName)
    }

    @Test
    fun `test parse extracts trust services from provider`() {
        val tsl = TslParser.parse(xmlContent)
        val provider = tsl.trustServiceProviders[0]
        assertEquals(2, provider.trustServices.size)
    }

    @Test
    fun `test parse extracts service type and status for granted CA QC service`() {
        val tsl = TslParser.parse(xmlContent)
        val service = tsl.trustServiceProviders[0].trustServices[0]

        assertEquals(TrustServiceEntry.TYPE_CA_QC, service.serviceType)
        assertEquals("Test CA QC Service", service.serviceName)
        assertEquals(TrustServiceEntry.STATUS_GRANTED, service.currentStatus)
        assertTrue(service.isGranted)
        assertTrue(service.isQualified)
    }

    @Test
    fun `test parse extracts service type and status for withdrawn TSA service`() {
        val tsl = TslParser.parse(xmlContent)
        val service = tsl.trustServiceProviders[0].trustServices[1]

        assertEquals(TrustServiceEntry.TYPE_TSA, service.serviceType)
        assertEquals("Test TSA Service", service.serviceName)
        assertEquals(TrustServiceEntry.STATUS_WITHDRAWN, service.currentStatus)
        assertFalse(service.isGranted)
    }

    @Test
    fun `test parse extracts status starting time`() {
        val tsl = TslParser.parse(xmlContent)
        val firstService = tsl.trustServiceProviders[0].trustServices[0]
        val secondService = tsl.trustServiceProviders[0].trustServices[1]

        assertEquals(Instant.parse("2020-01-01T00:00:00Z"), firstService.statusStartingTime)
        assertEquals(Instant.parse("2023-06-15T00:00:00Z"), secondService.statusStartingTime)
    }

    @Test
    fun `test parse extracts digital identity with x509 subject name`() {
        val tsl = TslParser.parse(xmlContent)
        val service = tsl.trustServiceProviders[0].trustServices[0]
        val identity = service.serviceDigitalIdentity

        assertNotNull(identity)
        val subjectName = identity.x509SubjectName
        assertNotNull(subjectName)
        assertTrue(subjectName.contains("CN=Test Provider"))
    }

    @Test
    fun `test parse returns null digital identity when absent`() {
        val tsl = TslParser.parse(xmlContent)
        val service = tsl.trustServiceProviders[0].trustServices[1]

        assertNull(service.serviceDigitalIdentity)
    }

    @Test
    fun `test parse handles empty TSL with no providers`() {
        val minimalXml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <TrustServiceStatusList xmlns="http://uri.etsi.org/02231/v2#">
                <SchemeInformation>
                    <SchemeOperatorName>
                        <Name xml:lang="en">Minimal Operator</Name>
                    </SchemeOperatorName>
                    <SchemeTerritory>XX</SchemeTerritory>
                </SchemeInformation>
            </TrustServiceStatusList>
        """.trimIndent()

        val tsl = TslParser.parse(minimalXml)

        assertEquals("XX", tsl.schemeTerritory)
        assertEquals("Minimal Operator", tsl.schemeOperatorName)
        assertTrue(tsl.trustServiceProviders.isEmpty())
        assertTrue(tsl.pointers.isEmpty())
        assertNull(tsl.sequenceNumber)
        assertNull(tsl.listIssueDate)
        assertNull(tsl.nextUpdate)
    }

    @Test
    fun `test parse handles TSL with empty provider list`() {
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <TrustServiceStatusList xmlns="http://uri.etsi.org/02231/v2#">
                <SchemeInformation>
                    <SchemeOperatorName>
                        <Name xml:lang="en">Empty Provider Operator</Name>
                    </SchemeOperatorName>
                    <SchemeTerritory>YY</SchemeTerritory>
                </SchemeInformation>
                <TrustServiceProviderList>
                </TrustServiceProviderList>
            </TrustServiceStatusList>
        """.trimIndent()

        val tsl = TslParser.parse(xml)

        assertEquals("YY", tsl.schemeTerritory)
        assertTrue(tsl.trustServiceProviders.isEmpty())
    }

    @Test
    fun `test parse handles TSL with pointers`() {
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <TrustServiceStatusList xmlns="http://uri.etsi.org/02231/v2#">
                <SchemeInformation>
                    <SchemeOperatorName>
                        <Name xml:lang="en">LOTL Operator</Name>
                    </SchemeOperatorName>
                    <SchemeTerritory>EU</SchemeTerritory>
                    <PointersToOtherTSL>
                        <OtherTSLPointer>
                            <TSLLocation>https://example.com/de-tl.xml</TSLLocation>
                            <SchemeTerritory>DE</SchemeTerritory>
                            <MimeType>application/vnd.etsi.tsl+xml</MimeType>
                        </OtherTSLPointer>
                        <OtherTSLPointer>
                            <TSLLocation>https://example.com/fr-tl.xml</TSLLocation>
                            <SchemeTerritory>FR</SchemeTerritory>
                        </OtherTSLPointer>
                    </PointersToOtherTSL>
                </SchemeInformation>
            </TrustServiceStatusList>
        """.trimIndent()

        val tsl = TslParser.parse(xml)

        assertEquals("EU", tsl.schemeTerritory)
        assertEquals(2, tsl.pointers.size)

        val dePointer = tsl.pointers[0]
        assertEquals("https://example.com/de-tl.xml", dePointer.location)
        assertEquals("DE", dePointer.schemeTerritory)
        assertEquals("application/vnd.etsi.tsl+xml", dePointer.mimeType)

        val frPointer = tsl.pointers[1]
        assertEquals("https://example.com/fr-tl.xml", frPointer.location)
        assertEquals("FR", frPointer.schemeTerritory)
        assertNull(frPointer.mimeType)
    }

    @Test
    fun `test parse handles multiple providers`() {
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <TrustServiceStatusList xmlns="http://uri.etsi.org/02231/v2#">
                <SchemeInformation>
                    <SchemeOperatorName>
                        <Name xml:lang="en">Multi Provider Operator</Name>
                    </SchemeOperatorName>
                    <SchemeTerritory>AT</SchemeTerritory>
                </SchemeInformation>
                <TrustServiceProviderList>
                    <TrustServiceProvider>
                        <TSPInformation>
                            <TSPName>
                                <Name xml:lang="en">Provider Alpha</Name>
                            </TSPName>
                        </TSPInformation>
                        <TSPServices>
                            <TSPService>
                                <ServiceInformation>
                                    <ServiceTypeIdentifier>http://uri.etsi.org/TrstSvc/Svctype/CA/QC</ServiceTypeIdentifier>
                                    <ServiceName>
                                        <Name xml:lang="en">Alpha QC Service</Name>
                                    </ServiceName>
                                    <ServiceStatus>http://uri.etsi.org/TrstSvc/TrustedList/Svcstatus/granted</ServiceStatus>
                                </ServiceInformation>
                            </TSPService>
                        </TSPServices>
                    </TrustServiceProvider>
                    <TrustServiceProvider>
                        <TSPInformation>
                            <TSPName>
                                <Name xml:lang="en">Provider Beta</Name>
                            </TSPName>
                        </TSPInformation>
                        <TSPServices>
                            <TSPService>
                                <ServiceInformation>
                                    <ServiceTypeIdentifier>http://uri.etsi.org/TrstSvc/Svctype/TSA</ServiceTypeIdentifier>
                                    <ServiceName>
                                        <Name xml:lang="en">Beta TSA Service</Name>
                                    </ServiceName>
                                    <ServiceStatus>http://uri.etsi.org/TrstSvc/TrustedList/Svcstatus/withdrawn</ServiceStatus>
                                </ServiceInformation>
                            </TSPService>
                        </TSPServices>
                    </TrustServiceProvider>
                </TrustServiceProviderList>
            </TrustServiceStatusList>
        """.trimIndent()

        val tsl = TslParser.parse(xml)

        assertEquals(2, tsl.trustServiceProviders.size)
        assertEquals("Provider Alpha", tsl.trustServiceProviders[0].name)
        assertEquals("Provider Beta", tsl.trustServiceProviders[1].name)

        assertTrue(tsl.trustServiceProviders[0].trustServices[0].isGranted)
        assertFalse(tsl.trustServiceProviders[1].trustServices[0].isGranted)
    }

    @Test
    fun `test parse extracts x509 certificate from digital identity`() {
        val certBase64 = "MIICZjCCAc+gAwIBAgIBADANBgkqhkiG9w0BAQ0FADBQMQswCQYDVQQGEwJERTEQMA4GA1UECAwHQmF2YXJpYTEPMA0GA1UEBwwGTXVuaWNoMQ0wCwYDVQQKDARUZXN0MQ8wDQYDVQQDDAZUZXN0Q0EwHhcNMjQwMTAxMDAwMDAwWhcNMjUwMTAxMDAwMDAwWjBQMQswCQYDVQQGEwJERTEQMA4GA1UECAwHQmF2YXJpYTEPMA0GA1UEBwwGTXVuaWNoMQ0wCwYDVQQKDARUZXN0MQ8wDQYDVQQDDAZUZXN0Q0EwgZ8wDQYJKoZIhvcNAQEBBQADgY0AMIGJAoGBALRiMLAhL3Bg"
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <TrustServiceStatusList xmlns="http://uri.etsi.org/02231/v2#">
                <SchemeInformation>
                    <SchemeOperatorName>
                        <Name xml:lang="en">Cert Test Operator</Name>
                    </SchemeOperatorName>
                    <SchemeTerritory>DE</SchemeTerritory>
                </SchemeInformation>
                <TrustServiceProviderList>
                    <TrustServiceProvider>
                        <TSPInformation>
                            <TSPName>
                                <Name xml:lang="en">Cert Provider</Name>
                            </TSPName>
                        </TSPInformation>
                        <TSPServices>
                            <TSPService>
                                <ServiceInformation>
                                    <ServiceTypeIdentifier>http://uri.etsi.org/TrstSvc/Svctype/CA/QC</ServiceTypeIdentifier>
                                    <ServiceName>
                                        <Name xml:lang="en">Cert QC Service</Name>
                                    </ServiceName>
                                    <ServiceStatus>http://uri.etsi.org/TrstSvc/TrustedList/Svcstatus/granted</ServiceStatus>
                                    <ServiceDigitalIdentity>
                                        <DigitalId>
                                            <X509Certificate>$certBase64</X509Certificate>
                                        </DigitalId>
                                    </ServiceDigitalIdentity>
                                </ServiceInformation>
                            </TSPService>
                        </TSPServices>
                    </TrustServiceProvider>
                </TrustServiceProviderList>
            </TrustServiceStatusList>
        """.trimIndent()

        val tsl = TslParser.parse(xml)
        val identity = tsl.trustServiceProviders[0].trustServices[0].serviceDigitalIdentity

        assertNotNull(identity)
        assertNotNull(identity.x509Certificate)
        assertEquals(certBase64, identity.x509Certificate)
    }
}
