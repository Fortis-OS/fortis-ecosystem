package com.fortis.mobile.autofill

import java.security.SecureRandom

/**
 * Short-lived, in-memory hand-off between the autofill service and its
 * activities. Secrets (a password being saved) never go into Intent extras.
 */
private class Expiring<T>(private val ttlMs: Long) {
    private data class Item<T>(val value: T, val at: Long)
    private val items = mutableMapOf<String, Item<T>>()

    @Synchronized fun put(v: T): String {
        prune()
        val t = ByteArray(16).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
        items[t] = Item(v, System.currentTimeMillis())
        return t
    }

    @Synchronized fun take(t: String?): T? { prune(); return t?.let { items.remove(it)?.value } }
    @Synchronized fun peek(t: String?): T? { prune(); return t?.let { items[it]?.value } }

    private fun prune() {
        val now = System.currentTimeMillis()
        items.entries.removeAll { now - it.value.at > ttlMs }
    }
}

object PendingForms {
    private val store = Expiring<LoginForm>(5 * 60_000)
    fun put(f: LoginForm) = store.put(f)
    fun peek(t: String?) = store.peek(t)
}

data class PendingSave(val form: LoginForm, val username: String, val password: String)

object PendingSaves {
    private val store = Expiring<PendingSave>(5 * 60_000)
    fun put(s: PendingSave) = store.put(s)
    fun take(t: String?) = store.take(t)
}
