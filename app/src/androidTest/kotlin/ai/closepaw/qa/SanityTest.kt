package ai.closepaw.qa

import androidx.compose.material3.Text
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SanityTest {

    @get:Rule val compose = createComposeRule()

    @Test fun compose_test_harness_renders_text() {
        compose.setContent { Text("qa-sanity") }
        compose.onNodeWithText("qa-sanity").assertIsDisplayed()
    }
}
