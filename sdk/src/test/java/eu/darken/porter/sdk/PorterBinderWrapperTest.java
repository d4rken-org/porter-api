package eu.darken.porter.sdk;

import static eu.darken.porter.protocol.PorterProtocol.TRANSACTION_transactRemote;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.os.Binder;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import eu.darken.porter.protocol.PorterProtocol;

/** The parcel a wrapped binder writes for the server to forward. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, manifest = Config.NONE)
public class PorterBinderWrapperTest {

    private static final int TARGET_CODE = 7;
    private static final int TARGET_FLAGS = 42;
    private static final int PAYLOAD = 20816;

    /** Reads the forwarding parcel the way {@code Service#transactRemote} reads it. */
    private static class CapturingService extends FakePorterService {

        IBinder target;
        int code = -1;
        int flags = -1;
        int payload = -1;

        @Override
        public boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
            if (code != TRANSACTION_transactRemote) {
                return super.onTransact(code, data, reply, flags);
            }
            data.enforceInterface(PorterProtocol.DESCRIPTOR);
            this.target = data.readStrongBinder();
            this.code = data.readInt();
            this.flags = data.readInt();
            this.payload = data.readInt();
            return true;
        }
    }

    @After
    public void teardown() {
        Porter.resetForTest();
    }

    @Test
    public void theWrappedBinderCodeAndFlagsFollowTheToken() throws Exception {
        CapturingService fake = new CapturingService();
        Porter.onBinderReceived(fake, "eu.darken.porter.probe");
        IBinder original = new Binder();

        Parcel data = Parcel.obtain();
        try {
            data.writeInt(PAYLOAD);
            assertTrue(new PorterBinderWrapper(original).transact(TARGET_CODE, data, null, TARGET_FLAGS));
        } finally {
            data.recycle();
        }

        assertSame(original, fake.target);
        assertEquals(TARGET_CODE, fake.code);
        assertEquals(TARGET_FLAGS, fake.flags);
        assertEquals(PAYLOAD, fake.payload);
    }
}
