"""Drive the real browser over CDP: fill, origin checks, save new / changed."""
import asyncio, json, os, subprocess, sys, time, urllib.request
import websockets

EXT = os.environ["EXT_ID"]
CLI = os.environ["CLI"]
ok_count = 0

def check(cond, what):
    global ok_count
    if not cond:
        print(f"FAIL: {what}"); sys.exit(1)
    ok_count += 1; print(f"  ok  {what}")

class CDP:
    def __init__(self, ws): self.ws, self.n, self.waiting, self.events = ws, 0, {}, []
    async def reader(self):
        async for m in self.ws:
            d = json.loads(m)
            if "id" in d and d["id"] in self.waiting: self.waiting.pop(d["id"]).set_result(d)
            else: self.events.append(d)
    async def call(self, method, params=None, session=None):
        self.n += 1; f = asyncio.get_running_loop().create_future(); self.waiting[self.n] = f
        msg = {"id": self.n, "method": method, "params": params or {}}
        if session: msg["sessionId"] = session
        await self.ws.send(json.dumps(msg))
        d = await asyncio.wait_for(f, 20)
        if "error" in d: raise RuntimeError(f"{method}: {d['error']}")
        return d["result"]
    async def eval(self, session, expr):
        r = await self.call("Runtime.evaluate", {"expression": expr, "awaitPromise": True, "returnByValue": True}, session)
        if "exceptionDetails" in r: raise RuntimeError(json.dumps(r["exceptionDetails"])[:600])
        return r["result"].get("value")

def cli(*a): return subprocess.run([CLI, *a], capture_output=True, text=True, check=True).stdout

async def main():
    for _ in range(100):
        try: ver = json.load(urllib.request.urlopen(f"http://127.0.0.1:{os.environ['CDP_PORT']}/json/version")); break
        except Exception: await asyncio.sleep(0.2)
    async with websockets.connect(ver["webSocketDebuggerUrl"], max_size=None) as ws:
        c = CDP(ws); asyncio.create_task(c.reader())
        # Find the extension's service worker.
        sw = None
        for _ in range(100):
            ts = (await c.call("Target.getTargets"))["targetInfos"]
            sw = next((t for t in ts if t["type"] == "service_worker" and EXT in t["url"]), None)
            if sw: break
            await asyncio.sleep(0.2)
        check(sw is not None, "extension loaded with the pinned ID")
        bg = (await c.call("Target.attachToTarget", {"targetId": sw["targetId"], "flatten": True}))["sessionId"]
        for _ in range(100):
            if await c.eval(bg, "typeof __fortis") == "object": break
            await asyncio.sleep(0.1)

        async def open_page(url):
            t = (await c.call("Target.createTarget", {"url": url}))["targetId"]
            s = (await c.call("Target.attachToTarget", {"targetId": t, "flatten": True}))["sessionId"]
            for _ in range(100):
                if await c.eval(s, "document.readyState") == "complete": break
                await asyncio.sleep(0.1)
            await asyncio.sleep(0.5)  # content script at document_idle
            await c.call("Target.activateTarget", {"targetId": t})
            tab = await c.eval(bg, f"chrome.tabs.query({{url: {json.dumps(url)}}}).then(t => t[0])")
            return s, tab

        async def popup(op, tab, **kw):
            return await c.eval(bg, f"__fortis.onPopup({json.dumps(dict(op=op, **kw))}, {json.dumps(tab)})")

        st = await popup("status", None)
        check(st["state"] == "unlocked", "native messaging → host → agent works (vault unlocked)")

        page, tab = await open_page("http://localhost:8765/login-a.html")
        m = await popup("match", tab)
        check(m["host"] == "localhost" and len(m["entries"]) == 1, "the saved login matches this site")
        check("password" not in m["entries"][0], "match returns no secrets")
        r = await popup("fill", tab, id=m["entries"][0]["id"])
        check(r and r.get("filled"), "fill reported success")
        vals = await c.eval(page, "[u.value, p.value, document.getElementById('hidden').value]")
        check(vals == ["alice", "pw1", ""], f"fields filled, hidden field untouched ({vals})")
        t = await popup("totp", tab, id=m["entries"][0]["id"])
        check(len(t["code"]) == 6, "2FA code for the matching site")

        other, tab2 = await open_page("http://127.0.0.1:8765/login-b.html")
        m2 = await popup("match", tab2)
        check(m2["entries"] == [], "a different host gets nothing (127.0.0.1 ≠ localhost)")
        try:
            await popup("fill", tab2, id=m["entries"][0]["id"]); check(False, "fill on wrong site refused")
        except RuntimeError as e:
            check("forbidden" in str(e) or "not saved for this site" in str(e), "agent refuses a known id on the wrong site")
        check(await c.eval(other, "p.value") == "", "nothing was typed into the wrong site")

        # Save a new login.
        page3, tab3 = await open_page("http://localhost:8765/login-new.html")
        await c.eval(page3, "u.value='bob'; p.value='s3cret!'; document.getElementById('go').click(); true")
        await asyncio.sleep(1.0)
        p = await popup("pending", tab3)
        check(p and p["status"] == "new" and p["username"] == "bob" and "password" not in p, "submit → 'Save login?' pending (no password exposed to popup)")
        r = await popup("save", tab3)
        check(r["action"] == "created", "saved as a new login")
        lst = cli("list")
        check("bob" in lst and "localhost" in lst, "new login is in the vault (fortis list)")

        # Change the password of an existing login.
        page4, tab4 = await open_page("http://localhost:8765/login-chg.html")
        await c.eval(page4, "u.value='alice'; p.value='pw2'; document.getElementById('go').click(); true")
        await asyncio.sleep(1.0)
        p = await popup("pending", tab4)
        check(p and p["status"] == "changed", "changed password detected")
        r = await popup("save", tab4)
        check(r["action"] == "updated", "password updated")
        g = json.loads(cli("get", "Local", "--json"))
        check(g["entry_type"]["Login"]["password"] == "pw2" and g["entry_type"]["Login"]["totp"], "vault has the new password and kept the 2FA")

        # Same password again → no prompt.
        page5, tab5 = await open_page("http://localhost:8765/login-same.html")
        await c.eval(page5, "u.value='alice'; p.value='pw2'; document.getElementById('go').click(); true")
        await asyncio.sleep(1.0)
        check(await popup("pending", tab5) is None, "unchanged password → no prompt")

        # Locked vault.
        await popup("lock", None)
        try:
            await popup("match", tab); check(False, "locked")
        except RuntimeError as e:
            check("vault_locked" in str(e), "locked vault → no matches until unlocked")
        try:
            await popup("unlock", None, password="wrong"); check(False, "wrong pw")
        except RuntimeError as e:
            check("invalid_password" in str(e), "wrong master password refused")
        await popup("unlock", None, password="test-master-password-123")
        check((await popup("status", None))["state"] == "unlocked", "unlock from the popup")
    print(f"\nall {ok_count} checks passed")

asyncio.run(main())
