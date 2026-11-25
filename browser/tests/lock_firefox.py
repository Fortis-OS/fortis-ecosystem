#!/usr/bin/env python3
"""Fortis Lock's error page in a real (headless, throw-away profile) Firefox.

Installs a test-only add-on that runs lock/pages/firefox.js + lock/page.js on a fake site
(chat.fortis-test.example, served over HTTPS on 127.0.0.1 with a strict CSP), the same
way the real extension registers them while locked. Checks that the real page never runs
and that Firefox's certificate error is drawn, styled and clickable. It doesn't talk to any
agent (a temporary Firefox add-on would reach your real one through the registered host).

    python3 browser/tests/lock_firefox.py [screenshot-dir]
"""
import asyncio, base64, http.server, json, os, shutil, ssl, subprocess, sys, tempfile, threading, time
import websockets

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC = os.path.join(ROOT, "extension", "src", "lock")
HOST = "chat.fortis-test.example"
SHOTS = sys.argv[1] if len(sys.argv) > 1 else None
ok_count = 0


def check(cond, what):
    global ok_count
    if not cond:
        print(f"FAIL: {what}")
        sys.exit(1)
    ok_count += 1
    print(f"  ok  {what}")


def serve(tmp):
    os.makedirs(f"{tmp}/www")
    with open(f"{tmp}/www/index.html", "w") as f:
        f.write('<!doctype html><title>Real Chat</title><script>window.realRan = true;</script><h1>Real messages</h1>')
    subprocess.run(["openssl", "req", "-x509", "-newkey", "rsa:2048", "-nodes", "-keyout", f"{tmp}/k.pem",
                    "-out", f"{tmp}/c.pem", "-days", "2", "-subj", f"/CN={HOST}"], check=True, capture_output=True)

    class H(http.server.SimpleHTTPRequestHandler):
        def __init__(s, *a, **k): super().__init__(*a, directory=f"{tmp}/www", **k)
        def log_message(s, *a): pass
        def end_headers(s):
            s.send_header("Content-Security-Policy", "default-src 'self'; script-src 'self' 'unsafe-inline'; style-src 'self'; img-src 'self'")
            super().end_headers()

    d = http.server.HTTPServer(("127.0.0.1", 8443), H)
    c = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    c.load_cert_chain(f"{tmp}/c.pem", f"{tmp}/k.pem")
    d.socket = c.wrap_socket(d.socket, server_side=True)
    threading.Thread(target=d.serve_forever, daemon=True).start()
    return d


async def main():
    firefox = shutil.which("firefox")
    if not firefox:
        print("firefox not installed; skipped")
        return
    tmp = tempfile.mkdtemp(prefix="fortis-lock-ff.")
    # The snap can't read /tmp; keep the profile and add-on in its own folder.
    snap = os.path.isdir("/snap/firefox")
    work = os.path.expanduser("~/snap/firefox/common/fortis-lock-test") if snap else f"{tmp}/ff"
    shutil.rmtree(work, ignore_errors=True)
    ext, prof = f"{work}/ext", f"{work}/profile"
    os.makedirs(f"{ext}/pages")
    os.makedirs(prof)
    shutil.copy(f"{SRC}/pages/firefox.js", f"{ext}/pages/firefox.js")
    shutil.copy(f"{SRC}/page.js", f"{ext}/page.js")
    json.dump({"manifest_version": 2, "name": "fortis-lock-test", "version": "1",
               "browser_specific_settings": {"gecko": {"id": "fortis-lock-test@fortis.local"}},
               "content_scripts": [{"matches": [f"https://{HOST}/*"], "js": ["pages/firefox.js", "page.js"],
                                    "run_at": "document_start"}]}, open(f"{ext}/manifest.json", "w"))
    with open(f"{prof}/user.js", "w") as f:
        f.write(f'user_pref("network.dns.localDomains", "{HOST}");\nuser_pref("network.dns.forceResolve", "127.0.0.1");\n'
                'user_pref("datareporting.policy.dataSubmissionEnabled", false);\n'
                'user_pref("browser.shell.checkDefaultBrowser", false);\n')
    httpd = serve(tmp)
    p = subprocess.Popen([firefox, "--headless", "--no-remote", "--profile", prof, "--remote-debugging-port", "9445"],
                         stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    ws = None
    try:
        for _ in range(120):
            try:
                ws = await websockets.connect("ws://127.0.0.1:9445/session", max_size=None)
                break
            except Exception:
                await asyncio.sleep(0.5)
        n = 0

        async def call(method, **params):
            nonlocal n
            n += 1
            await ws.send(json.dumps({"id": n, "method": method, "params": params}))
            while True:
                r = json.loads(await ws.recv())
                if r.get("id") == n:
                    if r.get("type") == "error": raise RuntimeError(f"{method}: {r.get('message')}")
                    return r.get("result")

        await call("session.new", capabilities={"alwaysMatch": {"acceptInsecureCerts": True}})
        await call("webExtension.install", extensionData={"type": "path", "path": ext})
        ctx = (await call("browsingContext.getTree"))["contexts"][0]["context"]

        async def ev(js):
            r = await call("script.evaluate", expression=js, target={"context": ctx}, awaitPromise=True,
                           resultOwnership="none", serializationOptions={"maxObjectDepth": 0})
            if r.get("type") == "exception": raise RuntimeError(json.dumps(r)[:500])
            return json.loads(r["result"]["value"]) if r["result"]["type"] == "string" else r["result"].get("value")

        async def shot(name):
            if SHOTS:
                s = await call("browsingContext.captureScreenshot", context=ctx)
                open(f"{SHOTS}/{name}.png", "wb").write(base64.b64decode(s["data"]))

        await call("browsingContext.navigate", context=ctx, url=f"https://{HOST}:8443/", wait="complete")
        await asyncio.sleep(1.5)
        info = await ev("""JSON.stringify((() => { const card = document.querySelector('net-error-card')?.shadowRoot;
            return { title: document.title, real: typeof window.realRan, url: location.href,
              h1: card?.querySelector('h1')?.textContent, intro: card?.querySelector('#error-intro')?.textContent,
              h1size: card && getComputedStyle(card.querySelector('h1')).fontSize,
              bg: getComputedStyle(document.documentElement).backgroundColor + " / " + getComputedStyle(document.body).backgroundColor,
              fox: !!card?.querySelector('.img-container svg, .img-container img'),
              foxBox: card?.querySelector('.img-container')?.getBoundingClientRect().width,
              btn: card?.querySelector('#returnButton')?.textContent?.trim() }; })())""")
        check(info["real"] == "undefined", "the real page's script never ran")
        check(info["title"] == "Warning: Security Risk", f"Firefox's tab title ({info['title']})")
        check(info["h1"] == "Be careful. Something doesn’t look right.", f"Firefox's heading ({info['h1']})")
        check(HOST in (info["intro"] or "") and ":8443" not in info["intro"], "names the real host")
        check(info["h1size"] not in (None, "", "32px") and info["bg"] != "rgba(0, 0, 0, 0) / rgba(0, 0, 0, 0)",
              f"styled despite the strict CSP (h1 {info['h1size']}, background {info['bg']})")
        await asyncio.sleep(0.5)
        fox = await ev("""JSON.stringify((() => { const c = document.querySelector('net-error-card').shadowRoot.querySelector('.img-container');
            const el = c.querySelector('svg, img'); const r = el.getBoundingClientRect(); return [el.localName, r.width, r.height]; })())""")
        check(fox[1] > 40 and fox[2] > 40, f"the fox is drawn ({fox})")
        check(info["btn"] == "Go back (Recommended)", "Firefox's button")
        await shot("firefox-locked")

        await ev("document.querySelector('net-error-card').shadowRoot.getElementById('advanced-button').click(); true")
        await asyncio.sleep(0.5)
        adv = await ev("""JSON.stringify([...document.querySelector('net-error-card').shadowRoot.querySelectorAll('h2, h3')].map(h => h.textContent.trim()).slice(0, 3))""")
        check(any("dangerous" in h for h in adv), f"Advanced shows the details ({adv})")
        await shot("firefox-advanced")

        await ev("""document.querySelector('net-error-card').shadowRoot.querySelector('.img-container')
            .dispatchEvent(new MouseEvent('click', {detail: 3, bubbles: true, composed: true})); true""")
        await asyncio.sleep(0.4)
        form = await ev("JSON.stringify([document.body.lastElementChild?.localName, document.body.lastElementChild?.getBoundingClientRect().width ?? 0])")
        check(form[0] == "div", "triple-clicking the fox opens the unlock form")
        await shot("firefox-unlock-form")
    finally:
        if ws:
            try:
                await call("browser.close")
            except Exception:
                pass
        time.sleep(2)
        httpd.shutdown()
        shutil.rmtree(tmp, ignore_errors=True)
        shutil.rmtree(work, ignore_errors=True)
    print(f"\nall {ok_count} checks passed")


if __name__ == "__main__":
    asyncio.run(main())
