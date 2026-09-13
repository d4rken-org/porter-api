package eu.darken.porter.client;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PermissionInfo;
import android.content.pm.Signature;
import android.content.pm.SigningInfo;
import android.os.Bundle;
import org.junit.Before;
import org.junit.Test;
import rikka.shizuku.ShizukuProvider;
import java.lang.reflect.Field;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class PorterClientTest {
    private final Context context = mock(Context.class);
    private final PackageManager packages = mock(PackageManager.class);
    private final SharedPreferences preferences = mock(SharedPreferences.class);
    private final Bundle metadata = mock(Bundle.class);

    @Before public void setUp() throws Exception {
        Field active = PorterClient.class.getDeclaredField("activeBackend");
        active.setAccessible(true);
        active.set(null, null);
        Field porterOnly = PorterClient.class.getDeclaredField("porterOnly");
        porterOnly.setAccessible(true);
        porterOnly.set(null, null);
        when(context.getPackageManager()).thenReturn(packages);
        when(context.getPackageName()).thenReturn("test.app");
        ApplicationInfo info = mock(ApplicationInfo.class);
        info.metaData = metadata;
        when(packages.getApplicationInfo("test.app", PackageManager.GET_META_DATA)).thenReturn(info);
        when(context.getSharedPreferences("porter.client", Context.MODE_PRIVATE)).thenReturn(preferences);
        when(preferences.getString("backend", "AUTO")).thenReturn("AUTO");
        when(packages.getPermissionInfo(PorterClient.PERMISSION, 0))
                .thenThrow(mock(PackageManager.NameNotFoundException.class));
        when(packages.getPermissionInfo(ShizukuProvider.PERMISSION, 0))
                .thenThrow(mock(PackageManager.NameNotFoundException.class));
    }

    @Test public void porterOnlyOverridesRestoredShizukuChoiceEvenWithoutPorter() {
        when(metadata.getBoolean(PorterClient.PORTER_ONLY, false)).thenReturn(true);
        when(preferences.getString("backend", "AUTO")).thenReturn("SHIZUKU");
        assertEquals(PorterClient.Backend.PORTER, PorterClient.getActiveBackend(context));
        assertThrows(IllegalArgumentException.class, () ->
                PorterClient.setBackendForNextProcess(context, PorterClient.Backend.SHIZUKU));
        assertThrows(IllegalArgumentException.class, () ->
                PorterClient.setBackendForNextProcess(context, PorterClient.Backend.AUTO));
        verifyNoInteractions(preferences);
    }

    @Test public void automaticChoiceStaysFixedWhenInstallationChanges() throws Exception {
        assertEquals(PorterClient.Backend.SHIZUKU, PorterClient.getActiveBackend(context));
        PermissionInfo permission = mock(PermissionInfo.class);
        permission.packageName = "eu.darken.porter";
        doReturn(permission).when(packages).getPermissionInfo(PorterClient.PERMISSION, 0);
        assertEquals(PorterClient.Backend.SHIZUKU, PorterClient.getActiveBackend(context));
    }

    @Test public void savingAnotherBackendDoesNotSwitchTheRunningProcess() {
        SharedPreferences.Editor editor = mock(SharedPreferences.Editor.class);
        when(preferences.edit()).thenReturn(editor);
        when(editor.putString("backend", "PORTER")).thenReturn(editor);
        when(editor.commit()).thenReturn(true);
        assertEquals(PorterClient.Backend.SHIZUKU, PorterClient.getActiveBackend(context));
        assertTrue(PorterClient.setBackendForNextProcess(context, PorterClient.Backend.PORTER));
        assertEquals(PorterClient.Backend.SHIZUKU, PorterClient.getActiveBackend(context));
    }

    @Test(expected = IllegalStateException.class)
    public void unreadableAppMetadataCannotFallBackToShizuku() throws Exception {
        when(packages.getApplicationInfo("test.app", PackageManager.GET_META_DATA))
                .thenThrow(mock(PackageManager.NameNotFoundException.class));
        PorterClient.getActiveBackend(context);
    }

    @Test public void autoSelectsPorterWhenItsPermissionExists() throws Exception {
        PermissionInfo permission = mock(PermissionInfo.class);
        permission.packageName = "eu.darken.porter";
        doReturn(permission).when(packages).getPermissionInfo(PorterClient.PERMISSION, 0);
        assertEquals(PorterClient.Backend.PORTER, PorterClient.getActiveBackend(context));
        doThrow(mock(PackageManager.NameNotFoundException.class)).when(packages).getPermissionInfo(PorterClient.PERMISSION, 0);
        assertEquals(PorterClient.Backend.PORTER, PorterClient.getActiveBackend(context));
    }

    @Test public void explicitShizukuChoiceWinsWhenPorterIsInstalled() throws Exception {
        PermissionInfo permission = mock(PermissionInfo.class);
        permission.packageName = "eu.darken.porter";
        doReturn(permission).when(packages).getPermissionInfo(PorterClient.PERMISSION, 0);
        when(preferences.getString("backend", "AUTO")).thenReturn("SHIZUKU");
        assertEquals(PorterClient.Backend.SHIZUKU, PorterClient.getActiveBackend(context));
    }

    @Test public void explicitPorterChoiceDoesNotFallBackWhenPorterIsMissing() {
        when(preferences.getString("backend", "AUTO")).thenReturn("PORTER");
        assertEquals(PorterClient.Backend.PORTER, PorterClient.getActiveBackend(context));
    }

    @Test public void unrecognizedPreferenceUsesAutomaticSelection() {
        when(preferences.getString("backend", "AUTO")).thenReturn("DELETED_BACKEND");
        assertEquals(PorterClient.Backend.SHIZUKU, PorterClient.getActiveBackend(context));
    }

    @Test public void failedPreferenceWriteDoesNotChangeActiveBackend() {
        SharedPreferences.Editor editor = mock(SharedPreferences.Editor.class);
        when(preferences.edit()).thenReturn(editor);
        when(editor.putString("backend", "PORTER")).thenReturn(editor);
        when(editor.commit()).thenReturn(false);
        assertEquals(PorterClient.Backend.SHIZUKU, PorterClient.getActiveBackend(context));
        assertFalse(PorterClient.setBackendForNextProcess(context, PorterClient.Backend.PORTER));
        assertEquals(PorterClient.Backend.SHIZUKU, PorterClient.getActiveBackend(context));
    }

    /** A selector binds to the preference; the active backend stays put until the next process. */
    @Test public void savedPreferenceIsReadableWhileTheActiveBackendStaysFixed() {
        SharedPreferences.Editor editor = mock(SharedPreferences.Editor.class);
        when(preferences.edit()).thenReturn(editor);
        when(editor.putString(eq("backend"), anyString())).thenReturn(editor);
        when(editor.commit()).thenReturn(true);

        assertEquals(PorterClient.Backend.AUTO, PorterClient.getPreferredBackend(context));
        assertEquals(PorterClient.Backend.SHIZUKU, PorterClient.getActiveBackend(context));

        assertTrue(PorterClient.setBackendForNextProcess(context, PorterClient.Backend.PORTER));
        when(preferences.getString("backend", "AUTO")).thenReturn("PORTER");

        assertEquals(PorterClient.Backend.PORTER, PorterClient.getPreferredBackend(context));
        assertEquals(PorterClient.Backend.SHIZUKU, PorterClient.getActiveBackend(context));
    }

    @Test public void resolvingAutomaticNeverReportsAutomatic() throws Exception {
        assertEquals(PorterClient.Backend.SHIZUKU,
                PorterClient.resolve(context, PorterClient.Backend.AUTO));
        installPermission(PorterClient.PERMISSION, "eu.darken.porter");
        assertEquals(PorterClient.Backend.PORTER,
                PorterClient.resolve(context, PorterClient.Backend.AUTO));
    }

    @Test public void porterIsRecognizedOnlyUnderItsOwnPackage() throws Exception {
        assertEquals(PorterClient.Manager.NONE,
                PorterClient.getManager(context, PorterClient.Backend.PORTER));
        installPermission(PorterClient.PERMISSION, "com.example.squatter");
        assertEquals(PorterClient.Manager.UNKNOWN,
                PorterClient.getManager(context, PorterClient.Backend.PORTER));
        installPermission(PorterClient.PERMISSION, "eu.darken.porter");
        assertEquals(PorterClient.Manager.PORTER,
                PorterClient.getManager(context, PorterClient.Backend.PORTER));
    }

    /** The companion wears Shizuku's package name; only the signing identity separates them. */
    @Test public void companionIsDistinguishedFromShizukuBySigningIdentity() throws Exception {
        assertEquals(PorterClient.Manager.NONE,
                PorterClient.getManager(context, PorterClient.Backend.SHIZUKU));

        installPermission(ShizukuProvider.PERMISSION, ShizukuProvider.MANAGER_APPLICATION_ID);
        Signature porterSignature = mock(Signature.class);
        Signature otherSignature = mock(Signature.class);

        installPermission(PorterClient.PERMISSION, "eu.darken.porter");
        installSignature("eu.darken.porter", porterSignature);
        installSignature(ShizukuProvider.MANAGER_APPLICATION_ID, otherSignature);
        assertEquals(PorterClient.Manager.SHIZUKU,
                PorterClient.getManager(context, PorterClient.Backend.SHIZUKU));

        installSignature(ShizukuProvider.MANAGER_APPLICATION_ID, porterSignature);
        assertEquals(PorterClient.Manager.PORTER_COMPATIBILITY,
                PorterClient.getManager(context, PorterClient.Backend.SHIZUKU));
    }

    /** A package we cannot inspect must not be presented to the user as Shizuku. */
    @Test public void anUninspectablePackageIsNotReportedAsShizuku() throws Exception {
        installPermission(ShizukuProvider.PERMISSION, ShizukuProvider.MANAGER_APPLICATION_ID);
        assertEquals(PorterClient.Manager.UNKNOWN,
                PorterClient.getManager(context, PorterClient.Backend.SHIZUKU));
    }

    /** With Porter uninstalled there is no signature to compare, so the companion's own component decides. */
    @Test public void orphanedCompanionIsStillRecognizedWithoutPorter() throws Exception {
        installPermission(ShizukuProvider.PERMISSION, ShizukuProvider.MANAGER_APPLICATION_ID);
        installSignature(ShizukuProvider.MANAGER_APPLICATION_ID, mock(Signature.class));

        installActivity(ShizukuProvider.MANAGER_APPLICATION_ID,
                "moe.shizuku.manager.MainActivity");
        assertEquals("inspected successfully, no Porter component",
                PorterClient.Manager.SHIZUKU,
                PorterClient.getManager(context, PorterClient.Backend.SHIZUKU));

        installActivity(ShizukuProvider.MANAGER_APPLICATION_ID,
                "eu.darken.porter.compat.OpenPorterActivity");
        assertEquals(PorterClient.Manager.PORTER_COMPATIBILITY,
                PorterClient.getManager(context, PorterClient.Backend.SHIZUKU));
    }

    /** Without Porter, a package whose components cannot be listed stays unidentified. */
    @Test public void unlistableComponentsWithoutPorterAreNotReportedAsShizuku() throws Exception {
        installPermission(ShizukuProvider.PERMISSION, ShizukuProvider.MANAGER_APPLICATION_ID);
        installSignature(ShizukuProvider.MANAGER_APPLICATION_ID, mock(Signature.class));
        assertEquals(PorterClient.Manager.UNKNOWN,
                PorterClient.getManager(context, PorterClient.Backend.SHIZUKU));
    }

    @Test public void nullBackendIsRejected() {
        assertThrows(NullPointerException.class, () -> PorterClient.resolve(context, null));
        assertThrows(NullPointerException.class, () -> PorterClient.getManager(context, null));
    }

    @Test public void anotherAppUsingShizukusPermissionIsNotTreatedAsShizuku() throws Exception {
        installPermission(ShizukuProvider.PERMISSION, "com.example.fork");
        assertEquals(PorterClient.Manager.UNKNOWN,
                PorterClient.getManager(context, PorterClient.Backend.SHIZUKU));
    }

    private void installPermission(String permission, String owner) throws Exception {
        PermissionInfo info = mock(PermissionInfo.class);
        info.packageName = owner;
        doReturn(info).when(packages).getPermissionInfo(permission, 0);
    }

    private void installActivity(String packageName, String className) throws Exception {
        ActivityInfo activity = mock(ActivityInfo.class);
        activity.name = className;
        PackageInfo info = mock(PackageInfo.class);
        info.activities = new ActivityInfo[]{activity};
        doReturn(info).when(packages).getPackageInfo(packageName,
                PackageManager.GET_ACTIVITIES | PackageManager.MATCH_DISABLED_COMPONENTS);
    }

    /** Stubs both signature flags so the result does not depend on the simulated SDK level. */
    private void installSignature(String packageName, Signature signature) throws Exception {
        PackageInfo info = mock(PackageInfo.class);
        info.signatures = new Signature[]{signature};
        SigningInfo signing = mock(SigningInfo.class);
        when(signing.getApkContentsSigners()).thenReturn(new Signature[]{signature});
        info.signingInfo = signing;
        doReturn(info).when(packages)
                .getPackageInfo(packageName, PackageManager.GET_SIGNATURES);
        doReturn(info).when(packages)
                .getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES);
    }
}
