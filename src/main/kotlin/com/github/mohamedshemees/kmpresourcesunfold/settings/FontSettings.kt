package com.github.mohamedshemees.kmpresourcesunfold.settings

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.ide.passwordSafe.PasswordSafe

object FontSettings {
    private const val SERVICE_NAME = "KmpResourcesUnfoldFonts:GoogleFontsApiKey"

    private val credentialAttributes: CredentialAttributes
        get() = CredentialAttributes(SERVICE_NAME)

    fun getApiKey(): String? {
        return PasswordSafe.instance.getPassword(credentialAttributes)
    }

    fun saveApiKey(apiKey: String) {
        PasswordSafe.instance.setPassword(credentialAttributes, apiKey)
    }

    fun clearApiKey() {
        PasswordSafe.instance.setPassword(credentialAttributes, null)
    }
}
