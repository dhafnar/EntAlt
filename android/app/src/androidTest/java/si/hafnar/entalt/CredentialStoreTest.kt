package si.hafnar.entalt

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.security.KeyStore
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import si.hafnar.entalt.data.CredentialStore
import si.hafnar.entalt.data.Credentials
import si.hafnar.entalt.data.HueCredentials

@RunWith(AndroidJUnit4::class)
class CredentialStoreTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val name = "instrumentation-account.v1"
    private val alias = "entalt.instrumentation.account.v1"
    private fun store() = CredentialStore(context, name, alias)

    @Before fun prepare() { store().clear(); store().clearHue() }
    @After fun cleanup() { store().clear(); store().clearHue() }

    @Test fun encryptedAccountSurvivesNewStoreInstanceAndReplacement() {
        assertNull(store().load())
        val first = Credentials("instrumentation-user", "synthetic-password-one")
        store().save(first)
        assertEquals(first, store().load())
        val bytes = File(context.noBackupFilesDir, name).readBytes().toString(Charsets.ISO_8859_1)
        assertFalse(bytes.contains(first.username))
        assertFalse(bytes.contains(first.password))
        val replacement = Credentials("replacement-user", "synthetic-password-two")
        store().save(replacement)
        assertEquals(replacement, store().load())
        store().clear()
        assertNull(store().load())
    }

    @Test fun corruptedAccountIsRejected() {
        store().save(Credentials("test", "synthetic"))
        val file = File(context.noBackupFilesDir, name)
        val bytes = file.readBytes()
        bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
        file.writeBytes(bytes)
        assertTrue(runCatching { store().load() }.isFailure)
    }

    @Test fun missingKeyCannotSilentlyCreateAnEmptyAccount() {
        store().save(Credentials("test", "synthetic"))
        KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(alias) }
        assertTrue(runCatching { store().load() }.isFailure)
    }

    @Test fun encryptedHueCredentialsRoundTripAndClear() {
        assertNull(store().loadHue())
        val hue = HueCredentials("192.168.1.50", "synthetic-hue-app-key", "synthetic-client-key", "001788fffe01")
        store().saveHue(hue)
        assertEquals(hue, store().loadHue())
        val bytes = File(context.noBackupFilesDir, "$name.hue").readBytes().toString(Charsets.ISO_8859_1)
        assertFalse(bytes.contains(hue.appKey))
        assertFalse(bytes.contains(hue.clientKey!!))
        store().clearHue()
        assertNull(store().loadHue())
    }
}
