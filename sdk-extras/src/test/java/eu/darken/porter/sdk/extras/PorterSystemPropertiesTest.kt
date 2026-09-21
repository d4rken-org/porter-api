package eu.darken.porter.sdk.extras

import android.os.Bundle
import eu.darken.porter.protocol.PorterProtocol.CAPABILITIES_NONE
import eu.darken.porter.protocol.PorterProtocol.REPLY_CAPABILITIES
import eu.darken.porter.protocol.PorterProtocol.REPLY_PERMISSION_GRANTED
import eu.darken.porter.protocol.PorterProtocol.REPLY_PROTOCOL_VERSION
import eu.darken.porter.protocol.PorterProtocol.REPLY_SERVER_SECONTEXT
import eu.darken.porter.protocol.PorterProtocol.REPLY_SERVER_UID
import eu.darken.porter.protocol.PorterProtocol.REPLY_SHOULD_SHOW_REQUEST_PERMISSION_RATIONALE
import eu.darken.porter.sdk.Porter
import eu.darken.porter.sdk.PorterConnection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** What the getters send as a default, and what they make of the answer. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
internal class PorterSystemPropertiesTest {

    // No reset between tests: the SDK's test hook is internal to its module, and every test here
    // attaches a fresh fake, which replaces whatever the previous one left published.

    private class Attached(val fake: FakeExtrasService, val connection: PorterConnection)

    private fun attached(): Attached {
        val reply = Bundle()
        reply.putInt(REPLY_PROTOCOL_VERSION, 1)
        reply.putInt(REPLY_SERVER_UID, 2000)
        reply.putString(REPLY_SERVER_SECONTEXT, "u:r:shell:s0")
        reply.putBoolean(REPLY_PERMISSION_GRANTED, true)
        reply.putBoolean(REPLY_SHOULD_SHOW_REQUEST_PERMISSION_RATIONALE, false)
        reply.putLong(REPLY_CAPABILITIES, CAPABILITIES_NONE)

        val fake = FakeExtrasService()
        fake.attachReply = reply
        Porter.onBinderReceived(fake, PACKAGE)
        return Attached(fake, Porter.connection.value!!)
    }

    @Test
    fun getIntDecodesTheFormsParseIntRejects() {
        val (fake, connection) = attached().let { it.fake to it.connection }

        fake.propertyValue = "0x1f"
        assertEquals(31, connection.getSystemPropertyInt(KEY, 0))

        fake.propertyValue = "017"
        assertEquals(15, connection.getSystemPropertyInt(KEY, 0))
    }

    @Test
    fun getLongDecodesTheFormsParseLongRejects() {
        val (fake, connection) = attached().let { it.fake to it.connection }

        fake.propertyValue = "0x1f"
        assertEquals(31L, connection.getSystemPropertyLong(KEY, 0))

        fake.propertyValue = "017"
        assertEquals(15L, connection.getSystemPropertyLong(KEY, 0))
    }

    @Test
    fun getBooleanIsTrueOnlyForTheWordTrue() {
        val (fake, connection) = attached().let { it.fake to it.connection }

        fake.propertyValue = "true"
        assertTrue(connection.getSystemPropertyBoolean(KEY, false))

        fake.propertyValue = "TRUE"
        assertTrue(connection.getSystemPropertyBoolean(KEY, false))

        fake.propertyValue = "yes"
        assertFalse(connection.getSystemPropertyBoolean(KEY, true))

        fake.propertyValue = "1"
        assertFalse(connection.getSystemPropertyBoolean(KEY, true))
    }

    @Test
    fun getWithoutADefaultSendsNull() {
        val (fake, connection) = attached().let { it.fake to it.connection }
        fake.propertyValue = "34"

        assertEquals("34", connection.getSystemProperty(KEY))

        assertEquals(KEY, fake.readName)
        assertNull(fake.readDefault)
    }

    @Test
    fun getSendsTheDefaultItWasGiven() {
        val (fake, connection) = attached().let { it.fake to it.connection }

        assertEquals("fallback", connection.getSystemProperty(KEY, "fallback"))
        assertEquals("fallback", fake.readDefault)
    }

    @Test
    fun getIntEncodesTheDefaultAndReadsItBack() {
        val (fake, connection) = attached().let { it.fake to it.connection }

        assertEquals(7, connection.getSystemPropertyInt(KEY, 7))
        assertEquals("7", fake.readDefault)
    }

    @Test
    fun setReachesTheServer() {
        val (fake, connection) = attached().let { it.fake to it.connection }

        connection.setSystemProperty("persist.porter.test", "on")

        assertEquals("persist.porter.test", fake.writtenName)
        assertEquals("on", fake.writtenValue)
    }

    @Test
    fun getIntPropagatesAMalformedValue() {
        val (fake, connection) = attached().let { it.fake to it.connection }
        fake.propertyValue = "not-a-number"

        assertThrows(NumberFormatException::class.java) { connection.getSystemPropertyInt(KEY, 0) }
    }

    private companion object {
        const val PACKAGE = "eu.darken.porter.probe"
        const val KEY = "ro.build.version.sdk"
    }
}
