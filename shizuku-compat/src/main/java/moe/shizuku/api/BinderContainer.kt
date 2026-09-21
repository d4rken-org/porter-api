package moe.shizuku.api

import android.os.IBinder
import android.os.Parcel
import android.os.Parcelable
import androidx.annotation.RestrictTo
import androidx.annotation.RestrictTo.Scope.LIBRARY_GROUP_PREFIX

/**
 * The Parcelable a Shizuku server wraps its binder in. The fully-qualified name is what the
 * receiving side unparcels by, so it is the one thing here that cannot change.
 */
@RestrictTo(LIBRARY_GROUP_PREFIX)
public class BinderContainer(@JvmField public var binder: IBinder?) : Parcelable {

    private constructor(source: Parcel) : this(source.readStrongBinder())

    override fun describeContents(): Int = 0

    override fun writeToParcel(dest: Parcel, flags: Int) {
        dest.writeStrongBinder(binder)
    }

    public companion object {
        @JvmField
        public val CREATOR: Parcelable.Creator<BinderContainer> = object : Parcelable.Creator<BinderContainer> {
            override fun createFromParcel(source: Parcel): BinderContainer = BinderContainer(source)
            override fun newArray(size: Int): Array<BinderContainer?> = arrayOfNulls(size)
        }
    }
}
