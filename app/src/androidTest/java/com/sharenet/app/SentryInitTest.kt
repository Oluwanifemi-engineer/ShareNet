package com.sharenet.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.sentry.Sentry
import io.sentry.SentryLevel
import io.sentry.android.core.SentryAndroid
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Proves the opt-in crash-reporting path works end to end on a real device:
 * initializing the Sentry SDK with a DSN (as ShareNetApp does when
 * sentry.properties is present) must actually deliver an event to the
 * configured endpoint. A local ServerSocket stands in for the Sentry server,
 * so no account or network access is needed.
 */
@RunWith(AndroidJUnit4::class)
class SentryInitTest {

    @Test
    fun sentry_delivers_an_event_to_the_configured_dsn_endpoint() {
        val context = ApplicationProvider.getApplicationContext<Context>()

        // Fake Sentry server: accept one connection, read whatever arrives.
        val server = ServerSocket(0)
        val received = CountDownLatch(1)
        Thread {
            try {
                server.accept().use { socket ->
                    socket.soTimeout = 5_000
                    val buf = ByteArray(4096)
                    val total = try {
                        var read = 0
                        while (read < buf.size) {
                            val n = socket.getInputStream().read(buf, read, buf.size - read)
                            if (n < 0) break
                            read += n
                            if (read > 0) break // the envelope header is enough
                        }
                        read
                    } catch (_: Exception) {
                        0
                    }
                    if (total > 0) received.countDown()
                }
            } catch (_: Exception) {
                // server closed before a connection — the assertion below reports it
            }
        }.apply { name = "fake-sentry"; isDaemon = true }.start()

        try {
            SentryAndroid.init(context) { options ->
                options.dsn = "http://dummykey@127.0.0.1:${server.localPort}/1"
            }
            Sentry.captureMessage("sharenet-sentry-verification", SentryLevel.INFO)
            assertTrue(
                "Sentry did not deliver the event to the configured DSN endpoint",
                received.await(15, TimeUnit.SECONDS),
            )
        } finally {
            Sentry.close()
            runCatching { server.close() }
        }
    }
}
