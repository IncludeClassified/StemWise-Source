package app.stemwise.integration

import app.stemwise.qualification.deleteTestPreferencesDurably
import android.content.Context
import android.content.ContextWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.lang.reflect.InvocationTargetException
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MvsepTokenStoreInstrumentationTest {
    @Test
    fun failedSavedTokenReadPreservesEncryptedPreferences() {
        repeat(2) {
            val application = InstrumentationRegistry.getInstrumentation().targetContext
            val preferenceName = "mvsep-read-failure-${UUID.randomUUID()}"
            val preferences = application.getSharedPreferences(preferenceName, Context.MODE_PRIVATE)
            val isolated = object : ContextWrapper(application) {
                override fun getApplicationContext(): Context = this
                override fun getSharedPreferences(name: String, mode: Int) = preferences
            }
            val encrypted = "invalid-base64!"
            val iv = "synthetic-iv"
            try {
                check(preferences.edit().putString("mvsep_token", encrypted)
                    .putString("mvsep_token_iv", iv).commit())
                val type = Class.forName("app.stemwise.integration.SecureTokenStore")
                val store = type.getDeclaredConstructor(Context::class.java)
                    .apply { isAccessible = true }.newInstance(isolated)
                val failure = runCatching {
                    type.getDeclaredMethod("read").apply { isAccessible = true }.invoke(store)
                }.exceptionOrNull()
                assertTrue(failure is InvocationTargetException)
                assertEquals(encrypted, preferences.getString("mvsep_token", null))
                assertEquals(iv, preferences.getString("mvsep_token_iv", null))
            } finally {
                deleteTestPreferencesDurably(application, preferenceName)
            }
        }
    }
}
