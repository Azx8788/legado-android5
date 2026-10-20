package io.legado.app

import io.legado.app.help.http.ObsoleteUrlFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Exercises the production ThreadLocal replacement used by ObsoleteUrlFactory. */
class Api21CompatTest {

    @Test
    fun standardDateFormat_usesRfc1123UtcFormat() {
        val utc = TimeZone.getTimeZone("GMT")
        val calendar = Calendar.getInstance(utc).apply {
            set(2024, Calendar.JANUARY, 15, 8, 30, 0)
            set(Calendar.MILLISECOND, 0)
        }

        val formatted = ObsoleteUrlFactory.STANDARD_DATE_FORMAT.get().format(calendar.time)

        assertEquals("Mon, 15 Jan 2024 08:30:00 GMT", formatted)
    }

    @Test
    fun standardDateFormat_isIsolatedPerThread() {
        val mainThreadFormat = ObsoleteUrlFactory.STANDARD_DATE_FORMAT.get()
        val otherThreadFormat = AtomicReference<java.text.DateFormat>()
        val latch = CountDownLatch(1)
        val thread = Thread {
            otherThreadFormat.set(ObsoleteUrlFactory.STANDARD_DATE_FORMAT.get())
            latch.countDown()
        }

        thread.start()
        assertTrue(latch.await(3, TimeUnit.SECONDS))
        assertNotNull(otherThreadFormat.get())
        assertNotSame(mainThreadFormat, otherThreadFormat.get())
    }
}
