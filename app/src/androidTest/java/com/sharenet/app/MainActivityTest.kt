package com.sharenet.app

import android.view.View
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Device-side smoke test (run with a connected device/emulator):
 *
 *     JAVA_HOME=~/jdk21 ./gradlew :app:connectedDebugAndroidTest
 *
 * Verifies the main screen inflates and the primary controls are present.
 * The full Wi-Fi Direct flow needs real radios and is covered by
 * scripts/device-test.sh and scripts/two-device-test.sh instead.
 */
@RunWith(AndroidJUnit4::class)
class MainActivityTest {

    @Test
    fun launchesAndShowsPrimaryActions() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                assertNotNull(activity.findViewById<View>(R.id.toggleButton))
                assertNotNull(activity.findViewById<View>(R.id.clientToggleButton))
                assertNotNull(activity.findViewById<View>(R.id.osSettingsButton))
                assertNotNull(activity.findViewById<View>(R.id.privacyButton))
            }
        }
    }
}
