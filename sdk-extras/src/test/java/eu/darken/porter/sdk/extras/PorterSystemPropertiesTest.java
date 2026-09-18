package eu.darken.porter.sdk.extras;

import static eu.darken.porter.protocol.PorterProtocol.CAPABILITIES_NONE;
import static eu.darken.porter.protocol.PorterProtocol.REPLY_CAPABILITIES;
import static eu.darken.porter.protocol.PorterProtocol.REPLY_PERMISSION_GRANTED;
import static eu.darken.porter.protocol.PorterProtocol.REPLY_PROTOCOL_VERSION;
import static eu.darken.porter.protocol.PorterProtocol.REPLY_SERVER_SECONTEXT;
import static eu.darken.porter.protocol.PorterProtocol.REPLY_SERVER_UID;
import static eu.darken.porter.protocol.PorterProtocol.REPLY_SHOULD_SHOW_REQUEST_PERMISSION_RATIONALE;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import android.os.Bundle;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import eu.darken.porter.sdk.Porter;

/** What the getters send as a default, and what they make of the answer. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, manifest = Config.NONE)
public class PorterSystemPropertiesTest {

    private static final String PACKAGE = "eu.darken.porter.probe";
    private static final String KEY = "ro.build.version.sdk";

    @After
    public void teardown() {
        Porter.resetForTest();
    }

    private static FakeExtrasService attached() {
        Bundle reply = new Bundle();
        reply.putInt(REPLY_PROTOCOL_VERSION, 1);
        reply.putInt(REPLY_SERVER_UID, 2000);
        reply.putString(REPLY_SERVER_SECONTEXT, "u:r:shell:s0");
        reply.putBoolean(REPLY_PERMISSION_GRANTED, true);
        reply.putBoolean(REPLY_SHOULD_SHOW_REQUEST_PERMISSION_RATIONALE, false);
        reply.putLong(REPLY_CAPABILITIES, CAPABILITIES_NONE);

        FakeExtrasService fake = new FakeExtrasService();
        fake.attachReply = reply;
        Porter.onBinderReceived(fake, PACKAGE);
        return fake;
    }

    @Test
    public void getIntDecodesTheFormsParseIntRejects() {
        FakeExtrasService fake = attached();

        fake.propertyValue = "0x1f";
        assertEquals(31, PorterSystemProperties.getInt(KEY, 0));

        fake.propertyValue = "017";
        assertEquals(15, PorterSystemProperties.getInt(KEY, 0));
    }

    @Test
    public void getLongDecodesTheFormsParseLongRejects() {
        FakeExtrasService fake = attached();

        fake.propertyValue = "0x1f";
        assertEquals(31L, PorterSystemProperties.getLong(KEY, 0));

        fake.propertyValue = "017";
        assertEquals(15L, PorterSystemProperties.getLong(KEY, 0));
    }

    @Test
    public void getBooleanIsTrueOnlyForTheWordTrue() {
        FakeExtrasService fake = attached();

        fake.propertyValue = "true";
        assertTrue(PorterSystemProperties.getBoolean(KEY, false));

        fake.propertyValue = "TRUE";
        assertTrue(PorterSystemProperties.getBoolean(KEY, false));

        fake.propertyValue = "yes";
        assertFalse(PorterSystemProperties.getBoolean(KEY, true));

        fake.propertyValue = "1";
        assertFalse(PorterSystemProperties.getBoolean(KEY, true));
    }

    @Test
    public void getWithoutADefaultSendsNull() {
        FakeExtrasService fake = attached();
        fake.propertyValue = "34";

        assertEquals("34", PorterSystemProperties.get(KEY));

        assertEquals(KEY, fake.readName);
        assertNull(fake.readDefault);
    }

    @Test
    public void getSendsTheDefaultItWasGiven() {
        FakeExtrasService fake = attached();

        assertEquals("fallback", PorterSystemProperties.get(KEY, "fallback"));
        assertEquals("fallback", fake.readDefault);
    }

    @Test
    public void getIntEncodesTheDefaultAndReadsItBack() {
        FakeExtrasService fake = attached();

        assertEquals(7, PorterSystemProperties.getInt(KEY, 7));
        assertEquals("7", fake.readDefault);
    }

    @Test
    public void setReachesTheServer() {
        FakeExtrasService fake = attached();

        PorterSystemProperties.set("persist.porter.test", "on");

        assertEquals("persist.porter.test", fake.writtenName);
        assertEquals("on", fake.writtenValue);
    }

    @Test
    public void getIntPropagatesAMalformedValue() {
        FakeExtrasService fake = attached();
        fake.propertyValue = "not-a-number";

        assertThrows(NumberFormatException.class, () -> PorterSystemProperties.getInt(KEY, 0));
    }
}
