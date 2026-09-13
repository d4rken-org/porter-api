package eu.darken.porter.porsh;

import android.annotation.SuppressLint;
import android.system.ErrnoException;
import android.system.Os;

import java.io.FileDescriptor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

@SuppressLint("DiscouragedPrivateApi")
@SuppressWarnings("JavaReflectionMemberAccess")
class FileDescriptors {

    private static Method getInt;

    static {
        try {
            getInt = FileDescriptor.class.getDeclaredMethod("getInt$");
            getInt.setAccessible(true);
        } catch (ReflectiveOperationException ignored) {
        }
    }

    public static int getFd(FileDescriptor fileDescriptor) {
        try {
            //noinspection ConstantConditions
            return (int) getInt.invoke(fileDescriptor);
        } catch (IllegalAccessException | InvocationTargetException ignored) {
            return -1;
        }
    }

    public static void closeSilently(FileDescriptor fileDescriptor) {
        if (fileDescriptor == null) {
            return;
        }

        try {
            Os.close(fileDescriptor);
        } catch (ErrnoException ignored) {
        }
    }
}
