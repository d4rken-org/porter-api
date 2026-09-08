package eu.darken.porter.client;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.PermissionInfo;
import android.os.Bundle;
import org.junit.Before;
import org.junit.Test;
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
}
