package eu.darken.porter.porsh;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import android.os.Parcel;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import eu.darken.porter.protocol.PorterProtocol;
import rikka.shizuku.ShizukuApiConstants;

/**
 * Two endpoints run one shell service each. A transaction is answered by the instance whose code
 * range and interface token it was written for, and by no other.
 *
 * <p>Lives in {@code server-shared} because {@code porsh}'s own unit tests run with
 * {@code returnDefaultValues} and cannot build a {@link Parcel}.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, manifest = Config.NONE)
public class PorshServiceTokenTest {

    private static final String PORTER_TOKEN = PorterProtocol.DESCRIPTOR;
    private static final String LEGACY_TOKEN = ShizukuApiConstants.BINDER_DESCRIPTOR;
    private static final int PORTER_BASE = PorterProtocol.TRANSACTION_PORSH_BASE;
    private static final int LEGACY_BASE = 30000;

    private static PorshService service(String interfaceToken, int transactionCodeStart, PorshHostRegistry registry) {
        return new PorshService(interfaceToken, transactionCodeStart, registry, new EnvPolicy(false)) {

            @Override
            public void enforceCallingPermission(String func) {
            }
        };
    }

    @Test
    public void aCodeIsAnsweredOnlyByTheInstanceThatOwnsIt() {
        PorshHostRegistry porterHosts = mock(PorshHostRegistry.class);
        PorshHostRegistry legacyHosts = mock(PorshHostRegistry.class);
        PorshService porter = service(PORTER_TOKEN, PORTER_BASE, porterHosts);
        PorshService legacy = service(LEGACY_TOKEN, LEGACY_BASE, legacyHosts);

        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(PORTER_TOKEN);

            data.setDataPosition(0);
            assertTrue(porter.onTransact(PORTER_BASE + PorshConfig.TRANSACTION_getExitCode, data, reply, 0));
            verify(porterHosts).getExitCode(anyInt());

            data.setDataPosition(0);
            assertFalse(legacy.onTransact(PORTER_BASE + PorshConfig.TRANSACTION_getExitCode, data, reply, 0));
            verifyNoInteractions(legacyHosts);
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    @Test
    public void theOwnedCodeUnderAnotherTokenIsRefused() {
        PorshHostRegistry hosts = mock(PorshHostRegistry.class);
        PorshService porter = service(PORTER_TOKEN, PORTER_BASE, hosts);

        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(LEGACY_TOKEN);
            data.setDataPosition(0);

            assertThrows(SecurityException.class,
                    () -> porter.onTransact(PORTER_BASE + PorshConfig.TRANSACTION_getExitCode, data, reply, 0));

            verifyNoInteractions(hosts);
        } finally {
            data.recycle();
            reply.recycle();
        }
    }
}
