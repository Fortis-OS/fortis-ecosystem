"""Fortis Lock in a real browser over CDP (run by lock-e2e.sh, never on its own)."""
import asyncio, base64, json, os, signal, subprocess, sys, time, urllib.request
import websockets

EXT = os.environ["EXT_ID"]
CLI = os.environ["CLI"]
SITE = "https://chat.fortis-test.example/"
SHOTS = os.environ.get("SHOTS")
ok_count = 0


def check(cond, what):
    global ok_count
    if not cond:
        print(f"FAIL: {what}")
        sys.exit(1)
    ok_count += 1
    print(f"  ok  {what}")


def cli(*a, stdin=None):
    return subprocess.run([CLI, *a], capture_output=True, text=True, input=stdin)


class CDP:
    def __init__(self, ws): self.ws, self.n, self.waiting = ws, 0, {}

    async def reader(self):
        async for m in self.ws:
            d = json.loads(m)
            if "id" in d and d["id"] in self.waiting: self.waiting.pop(d["id"]).set_result(d)

    async def call(self, method, params=None, session=None):
        self.n += 1
        f = asyncio.get_running_loop().create_future()
        self.waiting[self.n] = f
        msg = {"id": self.n, "method": method, "params": params or {}}
        if session: msg["sessionId"] = session
        await self.ws.send(json.dumps(msg))
        d = await asyncio.wait_for(f, 30)
        if "error" in d: raise RuntimeError(f"{method}: {d['error']}")
        return d["result"]

    async def eval(self, session, expr):
        r = await self.call("Runtime.evaluate", {"expression": expr, "awaitPromise": True, "returnByValue": True}, session)
        if "exceptionDetails" in r: raise RuntimeError(json.dumps(r["exceptionDetails"])[:600])
        return r["result"].get("value")


async def until(fn, what, secs=10):
    end = time.time() + secs
    while time.time() < end:
        try:
            if await fn(): return True
        except Exception:
            pass
        await asyncio.sleep(0.2)
    check(False, what)


async def main():
    for _ in range(100):
        try:
            ver = json.load(urllib.request.urlopen(f"http://127.0.0.1:{os.environ['CDP_PORT']}/json/version"))
            break
        except Exception:
            await asyncio.sleep(0.2)
    async with websockets.connect(ver["webSocketDebuggerUrl"], max_size=None) as ws:
        c = CDP(ws)
        asyncio.create_task(c.reader())
        sw = None
        for _ in range(100):
            ts = (await c.call("Target.getTargets"))["targetInfos"]
            sw = next((t for t in ts if t["type"] == "service_worker" and EXT in t["url"]), None)
            if sw: break
            await asyncio.sleep(0.2)
        check(sw is not None, "extension loaded")
        bg = (await c.call("Target.attachToTarget", {"targetId": sw["targetId"], "flatten": True}))["sessionId"]
        await until(lambda: c.eval(bg, "typeof __fortisLock === 'object'"), "lock module running")
        await until(lambda: c.eval(bg, "__fortisLock.state.sites.includes('chat.fortis-test.example')"),
                    "extension follows the agent: sites arrive over the watch port")

        async def open_tab(url):
            t = (await c.call("Target.createTarget", {"url": url}))["targetId"]
            s = (await c.call("Target.attachToTarget", {"targetId": t, "flatten": True}))["sessionId"]
            await c.call("Page.enable", session=s)
            return t, s

        # The popup's lock card and Settings (lock off, a site set).
        _, pop = await open_tab(f"chrome-extension://{EXT}/popup.html")
        await until(lambda: c.eval(pop, "!document.getElementById('lockCard').hidden && typeof showLock === 'function'"),
                    "popup shows the Fortis Lock card")
        check("fortis-test" in await c.eval(pop, "document.getElementById('lockSites').textContent"), "popup lists the sites")

        # Unlocked: the real page loads.
        chat_t, chat = await open_tab(SITE)
        try:
            await until(lambda: c.eval(chat, "document.title === 'Real Chat' && window.realRan === true"), "unlocked: the real site loads")
        except SystemExit:
            print("   tab:", await c.eval(chat, "JSON.stringify([location.href, document.title, document.body?.innerText?.slice(0, 200)])"))
            raise

        # Lock from the CLI (what a desktop hotkey runs): the open tab turns into the error page.
        r = cli("browser-lock", "on")
        check(r.returncode == 0, "fortis browser-lock on")
        await until(lambda: c.eval(chat, "document.title === 'Privacy error'"), "open tab switched to the error page", 15)
        await asyncio.sleep(0.5)
        info = await c.eval(chat, """({ real: typeof window.realRan, h1: document.querySelector('h1')?.textContent,
            host: document.querySelector('#main-message strong')?.textContent, code: document.getElementById('error-code')?.textContent,
            url: location.href, sheets: document.adoptedStyleSheets.length, bg: getComputedStyle(document.body).backgroundColor,
            icon: document.querySelector('#icon canvas, #icon svg') ? 'repainted' : getComputedStyle(document.getElementById('icon')).backgroundImage.slice(0, 30) })""")
        check(info["real"] == "undefined", "the real page's script never ran")
        check(info["h1"] == "Your connection is not private", f"heading is Chrome's ({info['h1']})")
        check(info["host"] == "chat.fortis-test.example" and info["url"] == SITE, "names the real host; address unchanged")
        check("ERR_CERT_AUTHORITY_INVALID" in (info["code"] or ""), "shows the certificate error code")
        check(info["sheets"] >= 1, f"styled despite the site's strict CSP ({info['bg']})")
        await until(lambda: c.eval(chat, "!!document.querySelector('#icon canvas')"), "warning icon drawn despite the site's img-src CSP")
        if SHOTS:
            for scheme in ("light", "dark"):
                await c.call("Emulation.setEmulatedMedia", {"features": [{"name": "prefers-color-scheme", "value": scheme}]}, chat)
                await asyncio.sleep(0.3)
                shot = await c.call("Page.captureScreenshot", {}, chat)
                with open(f"{SHOTS}/locked-{scheme}.png", "wb") as f: f.write(base64.b64decode(shot["data"]))

        # A new tab is locked too, and "Advanced" works like Chrome's.
        _, chat2 = await open_tab(SITE + "?new")
        await until(lambda: c.eval(chat2, "document.title === 'Privacy error'"), "new tab opens on the error page")
        adv = await c.eval(chat2, """(() => { const b = document.getElementById('details-button'); b.click();
            return [document.getElementById('details').classList.contains('hidden'), b.textContent.trim()]; })()""")
        check(adv[0] is False and adv[1] == "Hide advanced", f"Advanced expands the details ({adv})")

        # Everything else the site would load is blocked, even from another page.
        _, other = await open_tab("http://127.0.0.1:8766/")
        await until(lambda: c.eval(other, "document.readyState === 'complete'"), "other page loaded")
        blocked = await c.eval(other, "fetch('https://chat.fortis-test.example/ping').then(() => 'loaded', () => 'blocked')")
        check(blocked == "blocked", "requests to the locked site are blocked")

        # The laptop can't change the sites while locked.
        r = cli("browser-lock", "remove", "chat.fortis-test.example")
        check(r.returncode != 0 and "lock is on" in r.stderr, "sites can't be removed while locked")

        # Hidden unlock: triple-click the icon, wrong password, then the right one.
        async def unlock_with(pw):
            await c.eval(chat, "document.getElementById('icon').dispatchEvent(new MouseEvent('click', {detail: 3, bubbles: true})); true")
            await asyncio.sleep(0.3)
            await c.call("Input.insertText", {"text": pw}, chat)
            for t in ("keyDown", "keyUp"):
                await c.call("Input.dispatchKeyEvent", {"type": t, "key": "Enter", "code": "Enter",
                                                        "windowsVirtualKeyCode": 13, **({"text": "\r"} if t == "keyDown" else {})}, chat)
        await c.call("Target.activateTarget", {"targetId": chat_t})
        await unlock_with("nope")
        await asyncio.sleep(1.5)
        check("ON" in cli("browser-lock", "status").stdout, "wrong password: still locked")
        await c.call("Input.dispatchKeyEvent", {"type": "keyDown", "key": "Escape", "code": "Escape", "windowsVirtualKeyCode": 27}, chat)
        await asyncio.sleep(0.2)
        await unlock_with("lock-pass")
        await until(lambda: c.eval(chat, "document.title === 'Real Chat' && window.realRan === true"),
                    "right password: unlocked and the real page reloaded", 15)
        check("off" in cli("browser-lock", "status").stdout, "agent agrees: lock is off")

        # Fail closed: agent down → the shortcut still locks; the agent learns it when it's back.
        os.kill(int(os.environ["AGENT_PID"]), signal.SIGTERM)
        await asyncio.sleep(1)
        await c.eval(bg, "__fortisLock.lockNow().then(() => true)")
        await until(lambda: c.eval(chat, "document.title === 'Privacy error'"), "locked with the agent down", 15)
        agent = subprocess.Popen([os.environ["AGENT"]], stdout=open(os.environ["T"] + "/agent2.log", "w"), stderr=subprocess.STDOUT)
        try:
            await until(lambda: c.eval(bg, "chrome.alarms.get('fortis-lock-reconnect').then(a => !!a)"), "reconnect alarm set")
            # Don't wait for the 30 s alarm: fire the reconnect the alarm would.
            await asyncio.sleep(1)
            async def agent_locked():
                return "ON" in cli("browser-lock", "status").stdout
            for _ in range(80):
                if await agent_locked(): break
                await c.eval(bg, "chrome.alarms.create('fortis-lock-reconnect', {when: Date.now()}); true")
                await asyncio.sleep(0.5)
            check(await agent_locked(), "agent restarted → told about the lock pressed while it was down")
            check(await c.eval(chat, "document.title") == "Privacy error", "and the tab stays locked")
        finally:
            agent.terminate()
            agent.wait()
    print(f"\nall {ok_count} checks passed")


asyncio.run(main())
