package eu.darken.porter.endpoint;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.os.Bundle;
import android.os.IBinder;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import eu.darken.porter.protocol.PorterProtocol;
import eu.darken.porter.server.IPorterApplication;
import eu.darken.porter.server.IPorterRemoteProcess;
import eu.darken.porter.server.IPorterService;
import eu.darken.porter.server.IPorterServiceConnection;
import rikka.shizuku.server.ConfigManager;

/** The constants a client writes on the wire, against what the generated stub answers. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, manifest = Config.NONE)
public class PorterProtocolTest {

    /** The AIDL ids occupy {@code FIRST_CALL_TRANSACTION + 1 .. + 17}. */
    private static final int LAST_AIDL_CODE = IBinder.FIRST_CALL_TRANSACTION + 17;

    private static IPorterService.Stub stub() {
        return new IPorterService.Stub() {
            @Override public Bundle attach(IPorterApplication application, Bundle args) { return null; }

            @Override public int getUid() { return 0; }

            @Override public int checkPermission(String permission) { return 0; }

            @Override public String getSELinuxContext() { return null; }

            @Override public String getSystemProperty(String name, String defaultValue) { return null; }

            @Override public void setSystemProperty(String name, String value) { }

            @Override public IPorterRemoteProcess newProcess(String[] cmd, String[] env, String dir) { return null; }

            @Override public int addUserService(IPorterServiceConnection conn, Bundle args) { return 0; }

            @Override public int removeUserService(IPorterServiceConnection conn, Bundle args) { return 0; }

            @Override public void requestPermission(int requestCode) { }

            @Override public boolean checkSelfPermission() { return false; }

            @Override public boolean shouldShowRequestPermissionRationale() { return false; }

            @Override public void exit() { }

            @Override public void attachUserService(IBinder binder, Bundle args) { }

            @Override public void dispatchPermissionConfirmationResult(
                    int requestUid, int requestPid, int requestCode, Bundle data) { }

            @Override public int getFlagsForUid(int uid, int mask) { return 0; }

            @Override public void updateFlagsForUid(int uid, int mask, int value) { }
        };
    }

    private static void assertOutsideTheAidlRange(int code) {
        assertTrue("code " + code,
                code < IBinder.FIRST_CALL_TRANSACTION || code > LAST_AIDL_CODE);
    }

    @Test
    public void theDescriptorMatchesTheGeneratedStub() throws Exception {
        assertEquals(PorterProtocol.DESCRIPTOR, stub().getInterfaceDescriptor());
    }

    @Test
    public void theRawCodesCannotCollideWithAnAidlId() {
        assertOutsideTheAidlRange(PorterProtocol.TRANSACTION_transactRemote);
        assertOutsideTheAidlRange(PorterProtocol.TRANSACTION_PORSH_BASE);
        assertOutsideTheAidlRange(PorterProtocol.TRANSACTION_PORSH_BASE + 1);
        assertOutsideTheAidlRange(PorterProtocol.TRANSACTION_PORSH_BASE + 2);
    }

    @Test
    public void theApplicationRangeIsAboveEverythingThisProtocolAllocates() {
        assertOutsideTheAidlRange(PorterProtocol.TRANSACTION_APP_BASE);
        assertTrue(PorterProtocol.TRANSACTION_APP_BASE > LAST_AIDL_CODE);
        assertTrue(PorterProtocol.TRANSACTION_APP_BASE > PorterProtocol.TRANSACTION_transactRemote);
        assertTrue(PorterProtocol.TRANSACTION_APP_BASE > PorterProtocol.TRANSACTION_PORSH_BASE + 2);
    }

    @Test
    public void theFlagValuesAreTheOnesTheConfigManagerStores() {
        assertEquals(ConfigManager.FLAG_ALLOWED, PorterProtocol.FLAG_ALLOWED);
        assertEquals(ConfigManager.FLAG_DENIED, PorterProtocol.FLAG_DENIED);
        assertEquals(ConfigManager.MASK_PERMISSION, PorterProtocol.MASK_PERMISSION);
    }
}
