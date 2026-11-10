package com.fortis.mobile.autofill

import android.app.PendingIntent
import android.content.Intent
import android.os.CancellationSignal
import android.service.autofill.AutofillService
import android.service.autofill.FillCallback
import android.service.autofill.FillRequest
import android.service.autofill.FillResponse
import android.service.autofill.SaveCallback
import android.service.autofill.SaveInfo
import android.service.autofill.SaveRequest
import android.widget.RemoteViews
import com.fortis.mobile.R
import com.fortis.mobile.vault.VaultRepository

/**
 * Android autofill (security §6.5). Fill always goes through
 * [AutofillAuthActivity] (fingerprint / screen lock); save through
 * [AutofillSaveActivity] (user confirms).
 */
class FortisAutofillService : AutofillService() {

    override fun onFillRequest(request: FillRequest, cancellationSignal: CancellationSignal, callback: FillCallback) {
        val structure = request.fillContexts.lastOrNull()?.structure ?: return callback.onSuccess(null)
        val form = FormParser.parse(structure)
        if (form.isEmpty || form.packageName == packageName || !vaultReady()) return callback.onSuccess(null)
        if (AutofillLogins.saveUrl(form) == null && form.webUrl != null) return callback.onSuccess(null) // e.g. plain http

        val auth = Intent(this, AutofillAuthActivity::class.java)
            .putExtra(AutofillAuthActivity.EXTRA_FORM, PendingForms.put(form))
        val sender = PendingIntent.getActivity(
            this, form.hashCode(), auth,
            PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_MUTABLE, // mutable: Android adds the assist structure
        ).intentSender
        val presentation = RemoteViews(packageName, R.layout.autofill_item).apply {
            setTextViewText(R.id.autofill_title, "Unlock Fortis")
            setTextViewText(R.id.autofill_subtitle, form.webUrl?.substringAfter("://") ?: "Fill from your vault")
        }
        val response = FillResponse.Builder()
            .setAuthentication(form.fillIds.toTypedArray(), sender, presentation)
        saveInfo(form)?.let { response.setSaveInfo(it) }
        callback.onSuccess(response.build())
    }

    override fun onSaveRequest(request: SaveRequest, callback: SaveCallback) {
        val structure = request.fillContexts.lastOrNull()?.structure ?: return callback.onSuccess()
        val form = FormParser.parse(structure)
        val pw = form.passwordValue
        if (pw.isNullOrEmpty() || form.packageName == packageName || !vaultReady() || AutofillLogins.saveUrl(form) == null) {
            return callback.onSuccess()
        }
        val token = PendingSaves.put(PendingSave(form, form.usernameValue.orEmpty(), pw))
        val i = Intent(this, AutofillSaveActivity::class.java).putExtra(AutofillSaveActivity.EXTRA_TOKEN, token)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        callback.onSuccess(PendingIntent.getActivity(this, token.hashCode(), i, PendingIntent.FLAG_IMMUTABLE).intentSender)
    }

    private fun saveInfo(form: LoginForm): SaveInfo? {
        if (form.passwords.isEmpty()) return null
        val type = SaveInfo.SAVE_DATA_TYPE_PASSWORD or (if (form.username != null) SaveInfo.SAVE_DATA_TYPE_USERNAME else 0)
        return SaveInfo.Builder(type, form.passwords.toTypedArray())
            .apply { form.username?.let { setOptionalIds(arrayOf(it)) } }
            .build()
    }

    private fun vaultReady() = VaultRepository.get(this).state().let {
        it == VaultRepository.State.Locked || it == VaultRepository.State.Unlocked
    }
}
