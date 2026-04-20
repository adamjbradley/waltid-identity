package id.walt.authop.templates

import io.ktor.server.application.ApplicationCall
import io.ktor.server.html.respondHtml
import kotlinx.html.attributes.enumEncode
import kotlinx.html.body
import kotlinx.html.div
import kotlinx.html.h1
import kotlinx.html.head
import kotlinx.html.id
import kotlinx.html.li
import kotlinx.html.meta
import kotlinx.html.ol
import kotlinx.html.p
import kotlinx.html.script
import kotlinx.html.style
import kotlinx.html.title
import kotlinx.html.unsafe

/**
 * Intermediate "running customer checks…" page rendered after consent accept
 * when the RP requested the `preferences` scope. Reuses the existing
 * flow-update SSE surface (`/api/flow-stream`) so nothing bespoke ships here
 * beyond presentation.
 *
 * Lifecycle (client-side):
 *  1. Open EventSource on `/api/flow-stream?sessionId=<flowSessionId>`.
 *  2. POST the demo-fire proxy (`/api/flow-demo-fire`) to kick the n8n
 *     workflow. Request body carries the same sessionId so callbacks land
 *     on this stream.
 *  3. For each step update, append a row.
 *  4. When the `aggregate` step arrives, navigate to
 *     `/consent/flow-done` — the server-side handler finishes the consent
 *     (mints auth code, redirects to the RP).
 *
 * Failure handling: if the stream reports a `failed` status, or the
 * EventSource errors out before aggregate lands, the page links to
 * `/consent/flow-done` anyway — the server side will detect the missing/failed
 * aggregate and surface an OIDC `server_error` redirect back to the RP.
 */
suspend fun ApplicationCall.respondPreferencesProgressPage(
    authRequestId: String,
    flowSessionId: String,
) {
    respondHtml {
        head {
            title("Running customer checks…")
            meta(charset = "utf-8")
            style {
                unsafe {
                    +"""
                    body { font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif; max-width: 520px; margin: 60px auto; padding: 0 20px; color: #1a202c; }
                    h1 { font-size: 20px; font-weight: 600; margin-bottom: 4px; }
                    #sub { color: #4a5568; font-size: 14px; margin-bottom: 24px; }
                    ol { list-style: none; padding: 0; margin: 0; }
                    li { display: flex; align-items: center; gap: 10px; padding: 10px 0; border-bottom: 1px solid #edf2f7; font-size: 14px; }
                    li:last-child { border-bottom: none; }
                    .dot { width: 10px; height: 10px; border-radius: 50%; background: #cbd5e0; flex-shrink: 0; transition: background .15s; }
                    li.done .dot { background: #38a169; }
                    li.failed .dot { background: #e53e3e; }
                    li.running .dot { background: #3182ce; animation: pulse 1.2s ease-in-out infinite; }
                    @keyframes pulse { 0%,100% { opacity: 1; } 50% { opacity: 0.35; } }
                    .label { flex: 1; }
                    .status { color: #718096; font-size: 12px; }
                    #footer { margin-top: 24px; font-size: 13px; color: #718096; }
                    """.trimIndent()
                }
            }
        }
        body {
            h1 { +"Running customer checks…" }
            p {
                id = "sub"
                +"This takes a few seconds. You'll be returned to the application automatically."
            }
            ol {
                id = "steps"
                li {
                    attributes["data-step"] = "dark-web"
                    div { attributes["class"] = "dot" }
                    div {
                        attributes["class"] = "label"
                        +"Fraud check (dark web)"
                    }
                    div { attributes["class"] = "status" }
                }
                li {
                    attributes["data-step"] = "preferences"
                    div { attributes["class"] = "dot" }
                    div {
                        attributes["class"] = "label"
                        +"Lifestyle preferences"
                    }
                    div { attributes["class"] = "status" }
                }
                li {
                    attributes["data-step"] = "first-party"
                    div { attributes["class"] = "dot" }
                    div {
                        attributes["class"] = "label"
                        +"Fraud check (first-party)"
                    }
                    div { attributes["class"] = "status" }
                }
                li {
                    attributes["data-step"] = "aggregate"
                    div { attributes["class"] = "dot" }
                    div {
                        attributes["class"] = "label"
                        +"Finalising"
                    }
                    div { attributes["class"] = "status" }
                }
            }
            p {
                id = "footer"
                +"Session: "
                +flowSessionId.take(8)
                +"…"
            }
            script {
                unsafe {
                    +"""
                    (async () => {
                        const flowSessionId = ${flowSessionId.quoteForJs()};
                        const mark = (step, status) => {
                            const li = document.querySelector(
                                'li[data-step="' + step + '"]'
                            );
                            if (!li) return;
                            li.classList.remove('running');
                            li.classList.add(status === 'completed' ? 'done' : status);
                            li.querySelector('.status').textContent = status;
                        };
                        const markRunning = (step) => {
                            const li = document.querySelector(
                                'li[data-step="' + step + '"]'
                            );
                            if (!li) return;
                            li.classList.add('running');
                            li.querySelector('.status').textContent = 'running';
                        };

                        // Subscribe first so we don't miss early events.
                        const es = new EventSource('/api/flow-stream?sessionId=' + encodeURIComponent(flowSessionId));
                        let done = false;
                        const finish = () => {
                            if (done) return;
                            done = true;
                            es.close();
                            // Let the server finish the consent + redirect.
                            // The server pulls the aggregate from the replay
                            // buffer, stamps it onto the AuthRequest, mints
                            // the code, 302s to the RP.
                            window.location.replace('/consent/flow-done');
                        };
                        es.onmessage = (e) => {
                            try {
                                const upd = JSON.parse(e.data);
                                mark(upd.step, upd.status || 'completed');
                                if (upd.step === 'aggregate' && upd.status === 'completed') {
                                    finish();
                                }
                            } catch (err) {
                                console.error(err);
                            }
                        };
                        es.onerror = () => {
                            // Let the server decide what to surface to the RP:
                            // it'll 302 with error=server_error if aggregate never landed.
                            finish();
                        };

                        // Visual cue that the first step is in progress even
                        // before n8n's first callback lands.
                        markRunning('dark-web');

                        // Fire the workflow through the same-origin proxy.
                        try {
                            const r = await fetch('/api/flow-demo-fire', {
                                method: 'POST',
                                headers: {'Content-Type': 'application/json'},
                                body: JSON.stringify({
                                    sessionId: flowSessionId,
                                    customerRef: 'cust_001',
                                    channel: 'consent-progress',
                                }),
                            });
                            if (!r.ok) {
                                // Bail — server will surface a server_error to the RP.
                                finish();
                            }
                        } catch (_) {
                            finish();
                        }
                    })();
                    """.trimIndent()
                }
            }
        }
    }
}

/**
 * Quote a string so it can be safely embedded as a JavaScript string literal
 * without risking XSS via `<`, `>`, `"`, `'`, backslash, or newlines. We
 * control the caller (always a UUID from our own flow store) but defence
 * in depth is cheap.
 */
private fun String.quoteForJs(): String {
    val sb = StringBuilder(length + 2)
    sb.append('"')
    for (c in this) {
        when (c) {
            '\\' -> sb.append("\\\\")
            '"' -> sb.append("\\\"")
            '\n' -> sb.append("\\n")
            '\r' -> sb.append("\\r")
            '<' -> sb.append("\\u003c")
            '>' -> sb.append("\\u003e")
            '&' -> sb.append("\\u0026")
            else -> sb.append(c)
        }
    }
    sb.append('"')
    return sb.toString()
}
