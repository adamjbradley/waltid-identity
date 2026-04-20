package id.walt.authop.templates

import io.ktor.server.application.ApplicationCall
import io.ktor.server.html.respondHtml
import kotlinx.html.body
import kotlinx.html.div
import kotlinx.html.h1
import kotlinx.html.head
import kotlinx.html.id
import kotlinx.html.meta
import kotlinx.html.ol
import kotlinx.html.p
import kotlinx.html.script
import kotlinx.html.style
import kotlinx.html.title
import kotlinx.html.unsafe

/**
 * Self-contained end-to-end demo for the flow-update SSE stream.
 *
 * The page auto-chains three calls, all same-origin so no CORS config on
 * n8n is required:
 *
 *  1. `POST /api/flow-kickoff` → receives a fresh sessionId.
 *  2. Opens `EventSource` on `/api/flow-stream?sessionId=...`.
 *  3. `POST /api/flow-demo-fire` with the sessionId — auth-op server-side
 *     calls the n8n webhook which then fires the 4 callbacks back.
 *
 * The DOM shows each callback as it arrives, in insertion order. Styling
 * is intentionally minimal — this is for integration-test eyeballing, not
 * end-user consumption. The page is not linked from anywhere; it exists
 * for local verification only.
 */
suspend fun ApplicationCall.respondFlowDemoPage() {
    respondHtml {
        head {
            title("Flow update demo")
            meta(charset = "utf-8")
            style {
                unsafe {
                    +"""
                    body { font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif; max-width: 820px; margin: 40px auto; padding: 0 20px; color: #222; }
                    h1 { margin-bottom: 4px; }
                    #meta { color: #666; font-size: 14px; margin-bottom: 24px; }
                    #events { margin: 0; padding: 0; list-style: none; }
                    .evt { border-left: 4px solid #3182ce; padding: 10px 14px; margin: 8px 0; background: #f7fafc; border-radius: 4px; }
                    .evt.complete { border-left-color: #38a169; }
                    .evt.failed { border-left-color: #e53e3e; background: #fff5f5; }
                    .evt .step { font-weight: 600; font-size: 14px; letter-spacing: .03em; text-transform: uppercase; }
                    .evt .status { float: right; font-size: 12px; color: #4a5568; }
                    .evt pre { margin: 6px 0 0; font-size: 12px; line-height: 1.4; overflow-x: auto; background: #fff; padding: 8px; border-radius: 3px; border: 1px solid #e2e8f0; }
                    #status { font-size: 13px; color: #4a5568; margin-top: 20px; }
                    """.trimIndent()
                }
            }
        }
        body {
            h1 { +"Flow update demo" }
            div {
                id = "meta"
                p { +"Kickoff → subscribe → fire → watch. All same-origin; server-side proxy to n8n." }
            }
            div {
                id = "status"
                +"Starting…"
            }
            ol {
                id = "events"
            }
            script {
                unsafe {
                    +"""
                    (async () => {
                        const statusEl = document.getElementById('status');
                        const list = document.getElementById('events');
                        const setStatus = (t) => { statusEl.textContent = t; };
                        const row = (update) => {
                            const li = document.createElement('li');
                            li.className = 'evt ' + (update.status || '');
                            const step = document.createElement('span');
                            step.className = 'step';
                            step.textContent = update.step || '(unknown)';
                            const status = document.createElement('span');
                            status.className = 'status';
                            status.textContent = update.status || '';
                            li.appendChild(step);
                            li.appendChild(status);
                            if (update.result !== undefined) {
                                const pre = document.createElement('pre');
                                pre.textContent = JSON.stringify(update.result, null, 2);
                                li.appendChild(pre);
                            }
                            if (update.error) {
                                const pre = document.createElement('pre');
                                pre.textContent = update.error;
                                li.appendChild(pre);
                            }
                            list.appendChild(li);
                        };

                        try {
                            setStatus('Minting flow session…');
                            const ko = await fetch('/api/flow-kickoff', {
                                method: 'POST',
                                headers: {'Content-Type': 'application/json'},
                                body: '{}'
                            });
                            if (!ko.ok) throw new Error('kickoff failed: ' + ko.status);
                            const { sessionId, streamUrl } = await ko.json();
                            setStatus('Session ' + sessionId.slice(0, 8) + '…  subscribing…');

                            const es = new EventSource(streamUrl);
                            es.onmessage = (e) => {
                                try { row(JSON.parse(e.data)); } catch (err) { console.error(err); }
                            };
                            es.onerror = () => { setStatus('Stream closed'); };

                            // Small delay so the SSE connection is established before we fire.
                            // Not required for correctness (replay buffer would cover us) but
                            // gives a clean "everything in order" visual.
                            await new Promise((r) => setTimeout(r, 250));

                            setStatus('Firing workflow…');
                            const fire = await fetch('/api/flow-demo-fire', {
                                method: 'POST',
                                headers: {'Content-Type': 'application/json'},
                                body: JSON.stringify({ sessionId, customerRef: 'cust_001', channel: 'flow-demo' })
                            });
                            if (!fire.ok) {
                                const body = await fire.text();
                                setStatus('Fire failed (' + fire.status + '): ' + body.slice(0, 200));
                                return;
                            }
                            setStatus('Workflow complete');
                        } catch (err) {
                            setStatus('Error: ' + err.message);
                        }
                    })();
                    """.trimIndent()
                }
            }
        }
    }
}
