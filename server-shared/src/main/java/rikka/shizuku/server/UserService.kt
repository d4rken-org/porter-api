package rikka.shizuku.server

import android.app.ActivityThread
import android.app.Application
import android.app.Instrumentation
import android.content.Context
import android.content.ContextHidden
import android.ddm.DdmHandleAppName
import android.os.Build
import android.os.IBinder
import android.os.UserHandle
import android.os.UserHandleHidden
import android.util.Log
import android.util.Pair
import dev.rikka.tools.refine.Refine
import java.lang.reflect.Constructor

object UserService {

    private var TAG: String? = null

    fun setTag(tag: String) {
        TAG = tag
    }

    fun create(args: Array<String>): Pair<IBinder, String?>? {
        var name: String? = null
        var token: String? = null
        var pkg: String? = null
        var cls: String? = null
        var uid = -1

        for (arg in args) {
            if (arg.startsWith("--debug-name=")) {
                name = arg.substring(13)
            } else if (arg.startsWith("--token=")) {
                token = arg.substring(8)
            } else if (arg.startsWith("--package=")) {
                pkg = arg.substring(10)
            } else if (arg.startsWith("--class=")) {
                cls = arg.substring(8)
            } else if (arg.startsWith("--uid=")) {
                uid = arg.substring(6).toInt()
            }
        }

        val userId = uid / 100000

        Log.i(TAG, String.format("starting service %s/%s...", pkg, cls))

        val service: IBinder

        try {
            val activityThread = ActivityThread.systemMain()
            val systemContext = activityThread.systemContext

            DdmHandleAppName.setAppName(name ?: "$pkg:user_service", userId)

            //noinspection InstantiationOfUtilityClass
            val userHandle: UserHandle = Refine.unsafeCast(
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    UserHandleHidden.of(userId)
                } else {
                    UserHandleHidden(userId)
                },
            )
            val context = Refine.unsafeCast<ContextHidden>(systemContext)
                .createPackageContextAsUser(pkg, Context.CONTEXT_INCLUDE_CODE or Context.CONTEXT_IGNORE_SECURITY, userHandle)

            var application: Application? = null
            try {
                val mPackageInfo = context.javaClass.getDeclaredField("mPackageInfo")
                mPackageInfo.isAccessible = true
                val loadedApk = mPackageInfo.get(context)
                val makeApplication = loadedApk.javaClass.getDeclaredMethod("makeApplication", Boolean::class.javaPrimitiveType, Instrumentation::class.java)
                application = makeApplication.invoke(loadedApk, true, null) as Application
                val mInitialApplication = activityThread.javaClass.getDeclaredField("mInitialApplication")
                mInitialApplication.isAccessible = true
                mInitialApplication.set(activityThread, application)
            } catch (e: Throwable) {
                // Catch any errors initializing the application, and use the old Context method as a fallback instead
                // Especially relevant for MediaTek devices, see GitHub issue 1171
                Log.w(TAG, "Failed to initialize Application, using Context as fallback", e)
                application = null
            }

            val classLoader = application?.classLoader ?: context.classLoader
            val serviceClass = classLoader.loadClass(cls)
            var constructorWithContext: Constructor<*>? = null
            try {
                constructorWithContext = serviceClass.getConstructor(Context::class.java)
            } catch (ignored: NoSuchMethodException) {
            } catch (ignored: SecurityException) {
            }
            service = if (constructorWithContext != null) {
                constructorWithContext.newInstance(application ?: context) as IBinder
            } else {
                serviceClass.newInstance() as IBinder
            }
        } catch (tr: Throwable) {
            Log.w(TAG, String.format("unable to start service %s/%s...", pkg, cls), tr)
            return null
        }

        return Pair(service, token)
    }
}
