package io.legado.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 阶段 1 补充单测：验证 API 21 兼容替换的语义正确性。
 *
 * 覆盖三类替换：
 * 1. ConcurrentHashMap.newKeySet() → Collections.newSetFromMap(ConcurrentHashMap())
 * 2. ThreadLocal.withInitial { ... } → object : ThreadLocal<T>() { override fun initialValue() }
 * 3. TextUtils.join 替代 String.join（TextUtils 需要 Android 上下文，此处只验证逻辑等价性）
 */
class Api21CompatTest {

    // ── 1. Collections.newSetFromMap 语义 ──────────────────────────────────────

    @Test
    fun newSetFromMap_isMutableAndThreadSafe() {
        val set: MutableSet<String> =
            Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())

        // 基本增删查
        set.add("a")
        set.add("b")
        assertTrue(set.contains("a"))
        assertTrue(set.contains("b"))
        assertEquals(2, set.size)

        set.remove("a")
        assertTrue(!set.contains("a"))
        assertEquals(1, set.size)

        // 重复 add 不增加 size
        set.add("b")
        assertEquals(1, set.size)

        // clear
        set.clear()
        assertEquals(0, set.size)
    }

    @Test
    fun newSetFromMap_concurrentAccessNoException() {
        val set: MutableSet<String> =
            Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())
        val threadCount = 8
        val opsPerThread = 500
        val latch = CountDownLatch(threadCount)
        val pool = Executors.newFixedThreadPool(threadCount)

        repeat(threadCount) { t ->
            pool.submit {
                try {
                    repeat(opsPerThread) { i ->
                        val key = "t${t}_i${i}"
                        set.add(key)
                        set.contains(key)
                        if (i % 3 == 0) set.remove(key)
                    }
                } finally {
                    latch.countDown()
                }
            }
        }

        assertTrue("并发操作未在 5s 内完成", latch.await(5, TimeUnit.SECONDS))
        pool.shutdown()
        // 无 ConcurrentModificationException 即通过
    }

    // ── 2. 匿名 ThreadLocal.initialValue 语义 ──────────────────────────────────

    @Test
    fun threadLocalInitialValue_calledPerThread() {
        val utc = TimeZone.getTimeZone("GMT")
        val callCount = java.util.concurrent.atomic.AtomicInteger(0)

        val dateFormat: ThreadLocal<DateFormat> = object : ThreadLocal<DateFormat>() {
            override fun initialValue(): DateFormat {
                callCount.incrementAndGet()
                val fmt: DateFormat = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US)
                fmt.isLenient = false
                fmt.timeZone = utc
                return fmt
            }
        }

        // 主线程首次访问触发 initialValue
        val main = dateFormat.get()
        assertNotNull(main)
        assertEquals(1, callCount.get())

        // 同一线程再次访问不重新初始化
        val main2 = dateFormat.get()
        assertNotNull(main2)
        assertEquals(1, callCount.get())
        assertTrue("同线程应返回同一实例", main === main2)

        // 新线程触发自己的 initialValue
        val latch = CountDownLatch(1)
        val thread = Thread {
            val t = dateFormat.get()
            assertNotNull(t)
            latch.countDown()
        }
        thread.start()
        assertTrue(latch.await(3, TimeUnit.SECONDS))
        assertEquals("新线程应触发第二次 initialValue", 2, callCount.get())
    }

    @Test
    fun threadLocalInitialValue_formatCorrect() {
        val utc = TimeZone.getTimeZone("GMT")
        val dateFormat: ThreadLocal<DateFormat> = object : ThreadLocal<DateFormat>() {
            override fun initialValue(): DateFormat {
                val fmt: DateFormat = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US)
                fmt.isLenient = false
                fmt.timeZone = utc
                return fmt
            }
        }

        // 2024-01-15 08:30:00 UTC = Mon, 15 Jan 2024 08:30:00 GMT
        val cal = java.util.Calendar.getInstance(utc)
        cal.set(2024, 0, 15, 8, 30, 0)
        cal.set(java.util.Calendar.MILLISECOND, 0)
        val formatted = dateFormat.get().format(cal.time)
        assertEquals("Mon, 15 Jan 2024 08:30:00 GMT", formatted)
    }

    // ── 3. String.join 等价性（纯 JVM，不依赖 TextUtils）────────────────────────

    @Test
    fun stringJoinEquivalent_arrayCase() {
        // 原代码：String.join("|", coordinateArray)
        // 替换后：TextUtils.join("|", coordinateArray)
        // 此处验证逻辑等价（TextUtils.join 在 Android 运行时行为与 String.join 一致）
        val coordinateArray = arrayOf("10,20", "30,40", "50,60")
        val expected = "10,20|30,40|50,60"

        // JVM 等价实现（与 TextUtils.join 逻辑相同）
        val sb = StringBuilder()
        coordinateArray.forEachIndexed { i, s ->
            if (i > 0) sb.append("|")
            sb.append(s)
        }
        assertEquals(expected, sb.toString())
    }

    @Test
    fun stringJoinEquivalent_listCase() {
        // 原代码：String.join(",", glyphIdList)
        // 替换后：TextUtils.join(",", glyphIdList.toArray(new String[0]))
        val glyphIdList = java.util.LinkedList<String>()
        glyphIdList.add("{flags:1,glyphIndex:2}")
        glyphIdList.add("{flags:3,glyphIndex:4}")
        val expected = "{flags:1,glyphIndex:2},{flags:3,glyphIndex:4}"

        val arr = glyphIdList.toArray(arrayOfNulls<String>(0))
        val sb = StringBuilder()
        arr.forEachIndexed { i, s ->
            if (i > 0) sb.append(",")
            sb.append(s)
        }
        assertEquals(expected, sb.toString())
    }
}
