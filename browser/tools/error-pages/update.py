#!/usr/bin/env python3
"""Copy the real "Your connection is not private" pages out of the installed browsers.

Fortis Lock (docs/security §7.8) shows a copy of the browser's own certificate error. This
script makes those copies: it serves a self-signed certificate on 127.0.0.1, opens it in a
throw-away headless Chrome, Brave and Firefox profile (never your own profile), reads the
error page the browser renders and writes it as a template to
browser/extension/src/lock/pages/<browser>.js.

Run it again after a browser update changes the look:

    python3 -m venv /tmp/ep && /tmp/ep/bin/pip install websocket-client
    /tmp/ep/bin/python browser/tools/error-pages/update.py

Nothing is fetched from the network. Firefox's page uses Firefox-internal stylesheets and
images (chrome://…); those are read from the installed Firefox's omni.ja and inlined.
"""

import base64
import http.server
import json
import os
import re
import shutil
import ssl
import subprocess
import sys
import tempfile
import threading
import time
import urllib.request

import websocket  # websocket-client

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.normpath(os.path.join(HERE, "../../extension/src/lock/pages"))
HOST = "web.whatsapp.com"  # on Chrome's and Firefox's HSTS preload lists: no "proceed" link
PORT = 8443

CHROMIUMS = {
    "chrome": ["google-chrome", "google-chrome-stable", "chromium", "chromium-browser"],
    "brave": ["brave-browser", "brave"],
}


def which(names):
    for n in names:
        p = shutil.which(n)
        if p:
            return p
    return None


# ---- local HTTPS server with a self-signed certificate -------------------------------------

def serve(tmp):
    key, cert = os.path.join(tmp, "key.pem"), os.path.join(tmp, "cert.pem")
    subprocess.run(["openssl", "req", "-x509", "-newkey", "rsa:2048", "-nodes", "-keyout", key,
                    "-out", cert, "-days", "90", "-subj", f"/CN={HOST}"],
                   check=True, capture_output=True)
    www = os.path.join(tmp, "www")
    os.makedirs(www)
    with open(os.path.join(www, "index.html"), "w") as f:
        f.write("ok")

    class Quiet(http.server.SimpleHTTPRequestHandler):
        def __init__(self, *a, **k):
            super().__init__(*a, directory=www, **k)

        def log_message(self, *a):
            pass

    httpd = http.server.HTTPServer(("127.0.0.1", PORT), Quiet)
    ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    ctx.load_cert_chain(cert, key)
    httpd.socket = ctx.wrap_socket(httpd.socket, server_side=True)
    threading.Thread(target=httpd.serve_forever, daemon=True).start()
    return httpd


# ---- Chrome / Brave (DevTools protocol) ------------------------------------------------------

def capture_chromium(binary, tmp, port):
    prof = os.path.join(tmp, f"profile-{port}")
    p = subprocess.Popen([binary, "--headless=new", f"--remote-debugging-port={port}",
                          f"--user-data-dir={prof}", f"--host-resolver-rules=MAP {HOST} 127.0.0.1:{PORT}",
                          "--no-first-run", "--disable-gpu", "about:blank"],
                         stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    try:
        for _ in range(100):
            try:
                tabs = json.load(urllib.request.urlopen(f"http://127.0.0.1:{port}/json"))
                if any(t["type"] == "page" for t in tabs):
                    break
            except Exception:
                pass
            time.sleep(0.2)
        tab = next(t for t in tabs if t["type"] == "page")
        ws = websocket.create_connection(tab["webSocketDebuggerUrl"], suppress_origin=True)
        n = [0]

        def call(method, **params):
            n[0] += 1
            ws.send(json.dumps({"id": n[0], "method": method, "params": params}))
            while True:
                r = json.loads(ws.recv())
                if r.get("id") == n[0]:
                    return r.get("result")

        call("Page.navigate", url=f"https://{HOST}/")
        time.sleep(3)
        r = call("Runtime.evaluate", returnByValue=True, expression="""JSON.stringify({
            title: document.title, html: document.documentElement.outerHTML,
            closeDetails: (window.loadTimeData && loadTimeData.valueExists('closeDetails')) ? loadTimeData.getString('closeDetails') : null,
            openDetails: (window.loadTimeData && loadTimeData.valueExists('openDetails')) ? loadTimeData.getString('openDetails') : null })""")
        return json.loads(r["result"]["value"])
    finally:
        p.terminate()
        p.wait()


def chromium_template(cap):
    h = cap["html"]
    if "ERR_CERT" not in h:
        raise SystemExit("Chromium didn't show a certificate error page")
    html_attrs = dict(re.findall(r'(\w[\w-]*)="([^"]*)"', re.match(r"<html([^>]*)>", h).group(1)))
    inner = re.sub(r"^<html[^>]*>|</html>$", "", h.strip(), flags=re.S)
    inner = re.sub(r"<script\b[^>]*>.*?</script>", "", inner, flags=re.S)
    inner = re.sub(r'<link rel="stylesheet" href="chrome://[^"]*">', "", inner)
    css = re.findall(r"<style\b[^>]*>(.*?)</style>", inner, flags=re.S)
    inner = re.sub(r"<style\b[^>]*>.*?</style>", "", inner, flags=re.S)
    # Certificate dates → filled in when shown.
    inner = re.sub(r'(Expires on: </span><span class="debugging-content">)[^<]*', r"\1{{EXPIRES}}", inner)
    inner = re.sub(r'(Current date: </span><span class="debugging-content">)[^<]*', r"\1{{TODAY}}", inner)
    inner = inner.replace(HOST, "{{HOST}}")
    return {
        "kind": "chromium",
        "title": cap["title"],
        "htmlAttrs": html_attrs,
        "css": {"doc": "\n".join(css)},
        "main": inner,
        "openDetails": (cap.get("openDetails") or "Advanced").strip(),
        "closeDetails": (cap.get("closeDetails") or "Hide advanced").strip(),
    }


# ---- Firefox (WebDriver BiDi) ------------------------------------------------------------------

SERIALIZE = r"""(() => {
  const esc = (s) => s.replace(/&/g, '&amp;').replace(/</g, '&lt;');
  function ser(n) {
    if (n.nodeType === 3) return esc(n.textContent);
    if (n.nodeType !== 1) return '';
    let s = '<' + n.localName;
    for (const a of n.attributes) s += ' ' + a.name + '="' + a.value.replace(/&/g, '&amp;').replace(/"/g, '&quot;') + '"';
    s += '>';
    if (n.shadowRoot) { s += '<template shadowrootmode="open">'; for (const c of n.shadowRoot.childNodes) s += ser(c); s += '</template>'; }
    for (const c of (n.localName === 'template' ? n.content.childNodes : n.childNodes)) s += ser(c);
    return s + '</' + n.localName + '>';
  }
  return JSON.stringify({ title: document.title, html: ser(document.documentElement) });
})()"""


def capture_firefox(binary, tmp, pref_names=()):
    # Ubuntu's /usr/bin/firefox starts the snap, which can't see this script's /tmp.
    snap = os.path.isdir("/snap/firefox") and (os.path.realpath(binary).startswith("/snap") or
                                               b"snap" in open(binary, "rb").read(4096))
    prof = (os.path.expanduser("~/snap/firefox/common/fortis-error-pages-profile") if snap
            else os.path.join(tmp, "ff-profile"))
    shutil.rmtree(prof, ignore_errors=True)
    os.makedirs(prof)
    with open(os.path.join(prof, "user.js"), "w") as f:
        f.write(f'user_pref("network.dns.localDomains", "{HOST}");\n'
                'user_pref("network.dns.forceResolve", "127.0.0.1");\n'
                'user_pref("datareporting.policy.dataSubmissionEnabled", false);\n'
                'user_pref("toolkit.telemetry.reportingpolicy.firstRun", false);\n'
                'user_pref("browser.shell.checkDefaultBrowser", false);\n'
                'user_pref("layout.css.prefers-color-scheme.content-override", 1);\n')
    p = subprocess.Popen([binary, "--headless", "--no-remote", "--profile", prof,
                          "--remote-debugging-port", "9444", "--remote-allow-system-access"],
                         stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    ws = None
    n = [0]

    def call(method, **params):
        n[0] += 1
        ws.send(json.dumps({"id": n[0], "method": method, "params": params}))
        while True:
            r = json.loads(ws.recv())
            if r.get("id") == n[0]:
                if r.get("type") == "error":
                    raise RuntimeError(f"{method}: {r.get('message')}")
                return r.get("result")

    try:
        for _ in range(120):
            try:
                ws = websocket.create_connection("ws://127.0.0.1:9444/session", suppress_origin=True)
                break
            except Exception:
                time.sleep(0.5)
        call("session.new", capabilities={})
        ctx = call("browsingContext.getTree")["contexts"][0]["context"]
        try:
            call("browsingContext.navigate", context=ctx, url=f"https://{HOST}:{PORT}/", wait="complete")
        except RuntimeError:
            pass  # navigation "fails" with the certificate error; that page is what we want
        time.sleep(3)
        ev = lambda js: call("script.evaluate", expression=js, target={"context": ctx}, awaitPromise=False)
        main = json.loads(ev(SERIALIZE)["result"]["value"])
        ev("document.querySelector('net-error-card').shadowRoot.querySelector('#advanced-button').click()")
        time.sleep(1.5)
        adv = json.loads(ev(SERIALIZE)["result"]["value"])
        # The stylesheets gate parts of the design on prefs (`@media -moz-pref(...)`), which only
        # Firefox's own pages can evaluate. Read them from the browser's chrome context.
        chrome = call("browsingContext.getTree", **{"moz:scope": "chrome"})["contexts"][0]["context"]
        js = f"JSON.stringify(Object.fromEntries({json.dumps(sorted(pref_names))}.map(k => [k, Services.prefs.getBoolPref(k, false)])))"
        prefs = json.loads(call("script.evaluate", expression=js, target={"context": chrome}, awaitPromise=False)["result"]["value"])
        return main, adv, prefs
    finally:
        if ws:
            try:
                call("browser.close")
            except Exception:
                pass
        time.sleep(2)
        if p.poll() is None:
            try:
                p.terminate()
            except PermissionError:
                pass  # a snap process; browser.close above already ended it
        shutil.rmtree(prof, ignore_errors=True)


class Omni:
    """chrome:// URLs → files inside the installed Firefox's omni.ja."""

    def __init__(self, binary, tmp):
        self.prefs = {}
        # omni.ja is an "optimized" jar that Python's zipfile rejects; unzip copes.
        base = os.path.dirname(os.path.realpath(binary))
        cands = [base, "/snap/firefox/current/usr/lib/firefox", "/usr/lib/firefox", "/usr/lib/firefox-esr"]
        self.dirs = []
        for c in cands:
            for i, rel in enumerate(("omni.ja", "browser/omni.ja")):
                path = os.path.join(c, rel)
                if os.path.exists(path):
                    d = os.path.join(tmp, f"omni{i}")
                    subprocess.run(["unzip", "-qo", path, "-d", d], capture_output=True)
                    self.dirs.append(d)
            if self.dirs:
                break
        if not self.dirs:
            raise SystemExit("Firefox omni.ja not found")

    def read(self, url):
        m = re.match(r"chrome://(\w+)/(content|skin)/(.*)", url)
        if not m:
            raise KeyError(url)
        pkg, kind, rest = m.groups()
        name = {"skin": f"chrome/toolkit/skin/classic/{pkg}/{rest}",
                "content": f"chrome/toolkit/content/{pkg}/{rest}"}[kind]
        for d in self.dirs:
            for cand in (name, name.replace("chrome/toolkit/", "chrome/browser/")):
                path = os.path.join(d, cand)
                if os.path.isfile(path):
                    with open(path, "rb") as f:
                        return f.read()
        raise KeyError(url)

    def data_uri(self, url):
        b = self.read(url)
        mime = "image/svg+xml" if url.endswith(".svg") else "image/png"
        return f"data:{mime};base64,{base64.b64encode(b).decode()}"

    def css(self, url, seen=None, namespaces=None):
        """The stylesheet with every @import inlined and chrome:// images as data: URIs."""
        seen = set() if seen is None else seen
        if url in seen:
            return ""
        seen.add(url)
        try:
            text = self.read(url).decode()
        except KeyError:
            print(f"  (missing {url})")
            return ""

        def imp(m):
            return self.css(m.group(1), seen, namespaces)

        text = re.sub(r'@import\s+url\(["\']?(chrome://[^"\')]+)["\']?\)[^;]*;', imp, text)

        def ns(m):
            if namespaces is not None and m.group(1):
                namespaces[m.group(1)] = m.group(0)
            return ""  # unprefixed (default) namespaces would stop HTML selectors matching

        text = re.sub(r'@namespace\s+(\w+)?\s*(?:url\()?["\'][^"\']+["\']\)?\s*;', ns, text)

        def img(m):
            try:
                return f'url("{self.data_uri(m.group(1))}")'
            except KeyError:
                return 'url("")'

        text = re.sub(r'url\(["\']?(chrome://[^"\')]+\.(?:svg|png))["\']?\)', img, text)

        # `-moz-pref("x")` → an always-true / always-false condition, as the browser had it.
        def pref(m):
            return "(width >= 0px)" if self.prefs.get(m.group(1)) else "(width < 0px)"

        return re.sub(r'-moz-pref\(\s*"([^"]+)"\s*\)', pref, text)

    def pref_names(self):
        """Every pref named in a `-moz-pref("…")` condition in Firefox's stylesheets."""
        names = set()
        for d in self.dirs:
            for dirpath, _, files in os.walk(d):
                for f in files:
                    if f.endswith(".css"):
                        with open(os.path.join(dirpath, f), errors="replace") as fh:
                            names.update(re.findall(r'-moz-pref\(\s*"([^"]+)"\s*\)', fh.read()))
        return names

    def sheet(self, url):
        namespaces = {}
        body = self.css(url, namespaces=namespaces)
        return "\n".join(namespaces.values()) + "\n" + body


def firefox_template(main, adv, omni):
    css = {}

    def prep(h):
        h = re.sub(r"^<html[^>]*>|</html>$", "", h.strip(), flags=re.S)
        h = re.sub(r'<meta http-equiv="Content-Security-Policy"[^>]*></meta>', "", h)
        h = re.sub(r'<link rel="localization"[^>]*></link>', "", h)

        def link(m):
            url = m.group(1)
            key = url.rsplit("/", 1)[-1]
            if key not in css:
                css[key] = omni.sheet(url)
            return f'<style data-fortis-css="{key}"></style>'

        h = re.sub(r'<link rel="stylesheet" href="(chrome://[^"]+)"[^>]*></link>', link, h)
        h = re.sub(r'<link rel="icon"[^>]*></link>', "", h)

        def src(m):
            try:
                return f'src="{omni.data_uri(m.group(1))}"'
            except KeyError:
                return 'src=""'

        h = re.sub(r'src="(chrome://[^"]+)"', src, h)
        h = h.replace(f"{HOST}:{PORT}", "{{HOST}}").replace(HOST, "{{HOST}}")
        return h

    html_attrs = dict(re.findall(r'([\w-]+)="([^"]*)"', re.match(r"<html([^>]*)>", main["html"]).group(1)))
    html_attrs.pop("data-l10n-sync", None)
    return {
        "kind": "firefox",
        "title": main["title"],
        "htmlAttrs": html_attrs,
        "favicon": omni.data_uri("chrome://global/skin/icons/warning.svg"),
        "css": css,
        "main": prep(main["html"]),
        "advanced": prep(adv["html"]),
    }


def write(name, tpl):
    os.makedirs(OUT, exist_ok=True)
    path = os.path.join(OUT, f"{name}.js")
    with open(path, "w") as f:
        f.write("// Generated by browser/tools/error-pages/update.py from the installed browser. Don't edit.\n")
        f.write(f"globalThis.__fortisLockPage = {json.dumps(tpl, ensure_ascii=False)};\n")
    print(f"wrote {os.path.relpath(path)} ({os.path.getsize(path) // 1024} KiB)")


def main():
    only = set(sys.argv[1:])
    tmp = tempfile.mkdtemp(prefix="fortis-error-pages-")
    httpd = serve(tmp)
    try:
        port = 9333
        for name, names in CHROMIUMS.items():
            if only and name not in only:
                continue
            b = which(names)
            if not b:
                print(f"{name}: not installed, skipped")
                continue
            print(f"{name}: {b}")
            write(name, chromium_template(capture_chromium(b, tmp, port)))
            port += 1
        if not only or "firefox" in only:
            b = which(["firefox", "firefox-esr"])
            if b:
                print(f"firefox: {b}")
                omni = Omni(b, tmp)
                main_, adv, omni.prefs = capture_firefox(b, tmp, omni.pref_names())
                write("firefox", firefox_template(main_, adv, omni))
            else:
                print("firefox: not installed, skipped")
    finally:
        httpd.shutdown()
        shutil.rmtree(tmp, ignore_errors=True)


if __name__ == "__main__":
    main()
