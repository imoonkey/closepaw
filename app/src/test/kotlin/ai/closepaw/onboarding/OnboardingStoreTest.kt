package ai.closepaw.onboarding

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import org.junit.After
import org.junit.Before
import org.junit.Test

class OnboardingStoreTest {

    private lateinit var context: Context
    private lateinit var plainBacking: MutableMap<String, Any?>
    private lateinit var secureBacking: MutableMap<String, Any?>

    @Before
    fun setUp() {
        plainBacking = mutableMapOf()
        secureBacking = mutableMapOf()

        val plainPrefs = fakePrefs(plainBacking)
        val securePrefs = fakePrefs(secureBacking)

        context = mockk<Context>(relaxed = true) {
            every { getSharedPreferences("onboarding_prefs", any()) } returns plainPrefs
            every { getSharedPreferences("onboarding_secure_prefs", any()) } returns securePrefs
        }

        // MasterKey.Builder succeeds
        mockkConstructor(MasterKey.Builder::class)
        every {
            anyConstructed<MasterKey.Builder>().setKeyScheme(any())
        } returns mockk(relaxed = true) {
            every { build() } returns mockk(relaxed = true)
        }

        // Return fake encrypted prefs (uses same in-memory map)
        mockkStatic(EncryptedSharedPreferences::class)
        every {
            EncryptedSharedPreferences.create(
                any<Context>(),
                any<String>(),
                any<MasterKey>(),
                any<EncryptedSharedPreferences.PrefKeyEncryptionScheme>(),
                any<EncryptedSharedPreferences.PrefValueEncryptionScheme>()
            )
        } returns securePrefs
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    private fun fakePrefs(backing: MutableMap<String, Any?>): SharedPreferences {
        val editor = mockk<SharedPreferences.Editor>(relaxed = true)
        every { editor.putString(any(), any()) } answers {
            backing[firstArg()] = secondArg<String?>()
            editor
        }
        every { editor.putBoolean(any(), any()) } answers {
            backing[firstArg()] = secondArg<Boolean>()
            editor
        }
        every { editor.putInt(any(), any()) } answers {
            backing[firstArg()] = secondArg<Int>()
            editor
        }
        every { editor.remove(any()) } answers {
            backing.remove(firstArg<String>())
            editor
        }

        val prefs = mockk<SharedPreferences>(relaxed = true)
        every { prefs.edit() } returns editor
        every { prefs.getString(any(), any()) } answers {
            backing[firstArg()] as? String ?: secondArg()
        }
        every { prefs.getBoolean(any(), any()) } answers {
            backing[firstArg()] as? Boolean ?: secondArg()
        }
        every { prefs.getInt(any(), any()) } answers {
            backing[firstArg()] as? Int ?: secondArg()
        }
        every { prefs.contains(any()) } answers { backing.containsKey(firstArg()) }
        return prefs
    }

    @Test
    fun `saveOutcome persists and loadOutcomes round-trips each step`() {
        val store = OnboardingStore(context)

        store.saveOutcome(WizardStep.Accessibility, StepOutcome.Done)
        store.saveOutcome(WizardStep.Overlay, StepOutcome.Skipped)
        store.saveOutcome(WizardStep.Battery, StepOutcome.Done)
        store.saveOutcome(WizardStep.ApiKey, StepOutcome.Pending)
        store.saveOutcome(WizardStep.Demo, StepOutcome.Skipped)

        val outcomes = OnboardingStore(context).loadOutcomes()
        assertThat(outcomes.accessibility).isEqualTo(StepOutcome.Done)
        assertThat(outcomes.overlay).isEqualTo(StepOutcome.Skipped)
        assertThat(outcomes.battery).isEqualTo(StepOutcome.Done)
        assertThat(outcomes.apiKey).isEqualTo(StepOutcome.Pending)
        assertThat(outcomes.demo).isEqualTo(StepOutcome.Skipped)
    }

    @Test
    fun `saveAuthMethod persists and loadAuthMethod returns stored method`() {
        val store = OnboardingStore(context)

        assertThat(store.loadAuthMethod()).isNull()

        store.saveAuthMethod("oauth")
        assertThat(OnboardingStore(context).loadAuthMethod()).isEqualTo("oauth")

        store.saveAuthMethod("manual")
        assertThat(OnboardingStore(context).loadAuthMethod()).isEqualTo("manual")
    }

    @Test
    fun `saveApiKeyDraft writes to encrypted prefs and loadApiKeyDraft reads it back`() {
        val store = OnboardingStore(context)

        assertThat(store.loadApiKeyDraft()).isNull()

        store.saveApiKeyDraft("sk-secret-draft-123")
        assertThat(store.encryptionDegraded).isFalse()
        assertThat(secureBacking["onboarding_api_key_draft"]).isEqualTo("sk-secret-draft-123")

        val reloaded = OnboardingStore(context).loadApiKeyDraft()
        assertThat(reloaded).isEqualTo("sk-secret-draft-123")

        // Draft must not leak into plain prefs
        assertThat(plainBacking).doesNotContainKey("onboarding_api_key_draft")
    }

    @Test
    fun `clearApiKeyDraft removes the stored draft`() {
        val store = OnboardingStore(context)
        store.saveApiKeyDraft("sk-secret")
        assertThat(store.loadApiKeyDraft()).isEqualTo("sk-secret")

        store.clearApiKeyDraft()
        assertThat(store.loadApiKeyDraft()).isNull()
    }
}
