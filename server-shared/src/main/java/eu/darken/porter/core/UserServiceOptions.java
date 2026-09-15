package eu.darken.porter.core;

import android.content.ComponentName;

import androidx.annotation.Nullable;

import java.util.Objects;

/**
 * What a caller asked for when binding or unbinding a user service.
 *
 * <p>Only {@link #component} is validated here; everything a caller can get wrong is reported by the
 * manager, in the order the wire contract fixes.
 */
public final class UserServiceOptions {

    public final ComponentName component;
    @Nullable
    public final String tag;
    public final int versionCode;
    @Nullable
    public final String processNameSuffix;
    public final boolean debuggable;
    public final boolean noCreate;
    public final boolean daemon;
    public final boolean use32Bit;
    public final boolean remove;

    public UserServiceOptions(
            ComponentName component,
            @Nullable String tag,
            int versionCode,
            @Nullable String processNameSuffix,
            boolean debuggable,
            boolean noCreate,
            boolean daemon,
            boolean use32Bit,
            boolean remove) {
        this.component = Objects.requireNonNull(component, "component is null");
        this.tag = tag;
        this.versionCode = versionCode;
        this.processNameSuffix = processNameSuffix;
        this.debuggable = debuggable;
        this.noCreate = noCreate;
        this.daemon = daemon;
        this.use32Bit = use32Bit;
        this.remove = remove;
    }

    public String packageName() {
        return component.getPackageName();
    }

    public String className() {
        return component.getClassName();
    }

    /** {@code eu.darken.porter.probe:ProbeService}, or the tag in place of the class name. */
    public String key() {
        return packageName() + ":" + (tag != null ? tag : className());
    }
}
