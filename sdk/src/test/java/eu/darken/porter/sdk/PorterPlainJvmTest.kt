package eu.darken.porter.sdk

import org.junit.Assert.assertNull
import org.junit.Test

/** Without Robolectric, as an app's own JVM unit test that touches the SDK runs. */
internal class PorterPlainJvmTest {

    @Test
    fun readingTheConnectionNeedsNoMainLooper() {
        assertNull(Porter.connection.value)
    }
}
