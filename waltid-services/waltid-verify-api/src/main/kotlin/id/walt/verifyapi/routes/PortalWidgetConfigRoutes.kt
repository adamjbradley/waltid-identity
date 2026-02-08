package id.walt.verifyapi.routes

import id.walt.verifyapi.auth.AUTH_PORTAL_JWT
import id.walt.verifyapi.db.VerifyOrganizations
import id.walt.verifyapi.db.VerifyTemplates
import id.walt.verifyapi.portal.PortalUserPrincipal
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.http.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.time.Instant

private val logger = KotlinLogging.logger {}

/**
 * Response for widget configuration.
 */
@Serializable
data class WidgetConfigResponse(
    /** List of allowed origins for widget integration (CORS) */
    val allowedOrigins: List<String>,
    /** Available templates for widget use (system + organization-specific) */
    val availableTemplates: List<WidgetTemplateInfo>,
    /** ISO 8601 timestamp of last configuration update */
    val updatedAt: String?
)

/**
 * Template info for widget configuration.
 */
@Serializable
data class WidgetTemplateInfo(
    /** Unique template ID */
    val id: String,
    /** Template name (used as reference in API calls) */
    val name: String,
    /** Human-readable display name */
    val displayName: String?,
    /** Template description */
    val description: String?,
    /** Template type: "identity", "payment", "custom" */
    val type: String,
    /** Whether this is a system template (vs organization-specific) */
    val isSystem: Boolean
)

/**
 * Request to update widget configuration.
 */
@Serializable
data class UpdateWidgetConfigRequest(
    /** List of allowed origins for widget integration (CORS) */
    val allowedOrigins: List<String>
)

/**
 * Code snippet example for widget integration.
 */
@Serializable
data class WidgetCodeSnippet(
    /** Snippet language/framework */
    val language: String,
    /** Code snippet content */
    val code: String
)

/**
 * Response with code snippets for widget integration.
 */
@Serializable
data class WidgetSnippetsResponse(
    /** List of allowed origins (for display) */
    val allowedOrigins: List<String>,
    /** Code snippets for various frameworks */
    val snippets: List<WidgetCodeSnippet>
)

private val json = Json { prettyPrint = false }

// Pattern for validating origins
private val ORIGIN_PATTERN = Regex("""^https?://[a-zA-Z0-9][-a-zA-Z0-9]*(\.[a-zA-Z0-9][-a-zA-Z0-9]*)*(:\d{1,5})?$""")
private val WILDCARD_ORIGIN = "*"

/**
 * Configure widget configuration routes for the portal under /portal/widget/config.
 *
 * Provides endpoints for managing widget integration settings including
 * allowed origins for CORS and available templates.
 * Requires portal JWT authentication.
 */
fun Route.portalWidgetConfigRoutes() {
    authenticate(AUTH_PORTAL_JWT) {
        route("/portal/widget/config") {
            /**
             * GET /portal/widget/config
             *
             * Get widget configuration for the authenticated organization.
             * Returns allowed origins and available templates for widget integration.
             */
            get {
                val principal = call.principal<PortalUserPrincipal>()!!
                logger.debug { "Getting widget config for organization: ${principal.organizationId}" }

                val config = transaction {
                    // Get organization's widget config
                    val org = VerifyOrganizations.selectAll()
                        .where { VerifyOrganizations.id eq principal.organizationId }
                        .singleOrNull()
                        ?: throw IllegalStateException("Organization not found")

                    val allowedOriginsJson = org[VerifyOrganizations.widgetAllowedOrigins]
                    val allowedOrigins = if (allowedOriginsJson.isNullOrBlank()) {
                        emptyList()
                    } else {
                        try {
                            json.decodeFromString<List<String>>(allowedOriginsJson)
                        } catch (e: Exception) {
                            logger.warn { "Failed to parse widget_allowed_origins: ${e.message}" }
                            emptyList()
                        }
                    }

                    val updatedAt = org[VerifyOrganizations.updatedAt]

                    // Get available templates (system + org-specific)
                    val templates = VerifyTemplates.selectAll()
                        .where {
                            (VerifyTemplates.organizationId eq principal.organizationId) or
                            VerifyTemplates.organizationId.isNull()
                        }
                        .orderBy(VerifyTemplates.name to SortOrder.ASC)
                        .map { row ->
                            WidgetTemplateInfo(
                                id = row[VerifyTemplates.id].value.toString(),
                                name = row[VerifyTemplates.name],
                                displayName = row[VerifyTemplates.displayName],
                                description = row[VerifyTemplates.description],
                                type = row[VerifyTemplates.templateType],
                                isSystem = row[VerifyTemplates.organizationId] == null
                            )
                        }

                    WidgetConfigResponse(
                        allowedOrigins = allowedOrigins,
                        availableTemplates = templates,
                        updatedAt = updatedAt.toString()
                    )
                }

                call.respond(config)
            }

            /**
             * PUT /portal/widget/config
             *
             * Update widget configuration for the authenticated organization.
             * Currently supports updating allowed origins.
             */
            put {
                val principal = call.principal<PortalUserPrincipal>()!!
                val request = call.receive<UpdateWidgetConfigRequest>()

                logger.debug { "Updating widget config for organization: ${principal.organizationId}" }

                // Validate origins
                val validationErrors = validateOrigins(request.allowedOrigins)
                if (validationErrors.isNotEmpty()) {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        mapOf(
                            "error" to "Invalid origins",
                            "message" to "One or more origins are invalid",
                            "details" to validationErrors
                        )
                    )
                    return@put
                }

                // Limit number of origins
                if (request.allowedOrigins.size > 50) {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        mapOf(
                            "error" to "Too many origins",
                            "message" to "Maximum of 50 allowed origins supported"
                        )
                    )
                    return@put
                }

                val config = transaction {
                    val now = Instant.now()

                    // Update organization's widget config
                    val updated = VerifyOrganizations.update({
                        VerifyOrganizations.id eq principal.organizationId
                    }) {
                        it[widgetAllowedOrigins] = json.encodeToString(request.allowedOrigins)
                        it[updatedAt] = now
                    }

                    if (updated == 0) {
                        throw IllegalStateException("Organization not found")
                    }

                    // Get available templates for response
                    val templates = VerifyTemplates.selectAll()
                        .where {
                            (VerifyTemplates.organizationId eq principal.organizationId) or
                            VerifyTemplates.organizationId.isNull()
                        }
                        .orderBy(VerifyTemplates.name to SortOrder.ASC)
                        .map { row ->
                            WidgetTemplateInfo(
                                id = row[VerifyTemplates.id].value.toString(),
                                name = row[VerifyTemplates.name],
                                displayName = row[VerifyTemplates.displayName],
                                description = row[VerifyTemplates.description],
                                type = row[VerifyTemplates.templateType],
                                isSystem = row[VerifyTemplates.organizationId] == null
                            )
                        }

                    WidgetConfigResponse(
                        allowedOrigins = request.allowedOrigins,
                        availableTemplates = templates,
                        updatedAt = now.toString()
                    )
                }

                logger.info { "Updated widget config for organization: ${principal.organizationId}" }
                call.respond(config)
            }

            /**
             * GET /portal/widget/config/snippets
             *
             * Get code snippets for integrating the widget.
             * Returns copy-paste ready code for various frameworks showing
             * the current allowed origins configuration.
             */
            get("/snippets") {
                val principal = call.principal<PortalUserPrincipal>()!!
                logger.debug { "Getting widget snippets for organization: ${principal.organizationId}" }

                val (allowedOrigins, orgName) = transaction {
                    val org = VerifyOrganizations.selectAll()
                        .where { VerifyOrganizations.id eq principal.organizationId }
                        .singleOrNull()
                        ?: throw IllegalStateException("Organization not found")

                    val originsJson = org[VerifyOrganizations.widgetAllowedOrigins]
                    val origins = if (originsJson.isNullOrBlank()) {
                        emptyList()
                    } else {
                        try {
                            json.decodeFromString<List<String>>(originsJson)
                        } catch (e: Exception) {
                            emptyList()
                        }
                    }

                    origins to org[VerifyOrganizations.name]
                }

                val snippets = generateCodeSnippets(allowedOrigins, orgName)

                call.respond(WidgetSnippetsResponse(
                    allowedOrigins = allowedOrigins,
                    snippets = snippets
                ))
            }
        }
    }
}

/**
 * Validate a list of origins.
 * Returns a list of validation errors (empty if all valid).
 */
internal fun validateOrigins(origins: List<String>): List<Map<String, String>> {
    val errors = mutableListOf<Map<String, String>>()

    origins.forEachIndexed { index, origin ->
        val trimmed = origin.trim()

        when {
            trimmed.isBlank() -> {
                errors.add(mapOf(
                    "index" to index.toString(),
                    "origin" to origin,
                    "error" to "Origin cannot be blank"
                ))
            }
            trimmed == WILDCARD_ORIGIN -> {
                // Wildcard is allowed but discouraged
                // Don't add error, but could add warning in the future
            }
            trimmed.endsWith("/") -> {
                // Check trailing slash before pattern validation
                errors.add(mapOf(
                    "index" to index.toString(),
                    "origin" to origin,
                    "error" to "Origin must not end with a trailing slash"
                ))
            }
            !ORIGIN_PATTERN.matches(trimmed) -> {
                errors.add(mapOf(
                    "index" to index.toString(),
                    "origin" to origin,
                    "error" to "Invalid origin format. Must be http(s)://domain[:port]"
                ))
            }
        }
    }

    return errors
}

/**
 * Generate code snippets for various frameworks.
 */
private fun generateCodeSnippets(allowedOrigins: List<String>, orgName: String): List<WidgetCodeSnippet> {
    val exampleOrigin = allowedOrigins.firstOrNull() ?: "https://your-domain.com"
    val originsDisplay = if (allowedOrigins.isEmpty()) {
        "// No origins configured - widget will not work until you add allowed origins"
    } else {
        "// Allowed origins: ${allowedOrigins.joinToString(", ")}"
    }

    return listOf(
        WidgetCodeSnippet(
            language = "html",
            code = """
<!-- Verify Widget Integration for $orgName -->
$originsDisplay
<script src="https://verify-api.example.com/widget/v1/sdk.js"></script>
<script>
  const verifyWidget = new VerifyWidget({
    apiUrl: 'https://verify-api.example.com',
    clientToken: 'YOUR_CLIENT_TOKEN', // Generate via /v1/widget/tokens
    template: 'identity-basic',
    onSuccess: (result) => {
      console.log('Verification successful:', result);
    },
    onError: (error) => {
      console.error('Verification failed:', error);
    }
  });

  // Open the verification modal
  document.getElementById('verify-btn').addEventListener('click', () => {
    verifyWidget.open();
  });
</script>

<button id="verify-btn">Verify Identity</button>
            """.trimIndent()
        ),
        WidgetCodeSnippet(
            language = "react",
            code = """
// React Integration for $orgName
$originsDisplay
import { useVerify } from '@verifyapi/react';

function VerifyButton() {
  const { open, isReady } = useVerify({
    apiUrl: 'https://verify-api.example.com',
    clientToken: 'YOUR_CLIENT_TOKEN', // Generate via /v1/widget/tokens
    template: 'identity-basic',
    onSuccess: (result) => {
      console.log('Verification successful:', result);
    },
    onError: (error) => {
      console.error('Verification failed:', error);
    }
  });

  return (
    <button onClick={open} disabled={!isReady}>
      Verify Identity
    </button>
  );
}
            """.trimIndent()
        ),
        WidgetCodeSnippet(
            language = "nextjs",
            code = """
// Next.js Integration for $orgName
$originsDisplay

// pages/api/verify-token.ts (server-side token generation)
import type { NextApiRequest, NextApiResponse } from 'next';

export default async function handler(req: NextApiRequest, res: NextApiResponse) {
  const response = await fetch('https://verify-api.example.com/v1/widget/tokens', {
    method: 'POST',
    headers: {
      'X-API-Key': process.env.VERIFY_API_KEY!,
      'Content-Type': 'application/json'
    },
    body: JSON.stringify({
      allowedTemplates: ['identity-basic'],
      ttlMinutes: 30
    })
  });

  const data = await response.json();
  res.json({ token: data.token });
}

// components/VerifyButton.tsx (client-side)
'use client';
import { useEffect, useState } from 'react';

export function VerifyButton() {
  const [token, setToken] = useState<string | null>(null);

  useEffect(() => {
    fetch('/api/verify-token')
      .then(res => res.json())
      .then(data => setToken(data.token));
  }, []);

  // Use token with widget SDK
  // ...
}
            """.trimIndent()
        )
    )
}
