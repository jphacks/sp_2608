package com.theacar.android.navigation

import android.content.Context
import com.theacar.android.security.EncryptedSecretFile

internal class AndroidRoutingTokenStore(context: Context) : RoutingTokenStorage {
    private val file = EncryptedSecretFile(context, "routing-token.enc", "com.theacar.routing-token.v1")
    override fun read(): String = file.read()
    override fun write(token: String) = file.write(token)
}
