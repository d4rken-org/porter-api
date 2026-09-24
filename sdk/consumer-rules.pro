# ShizukuProtocolDelivery names this class, which only an app that adds the optional
# shizuku-compat artifact ships. PorterShizukuApiProvider refuses to attach without it.
-dontwarn moe.shizuku.api.BinderContainer

# The server instantiates a user service by name, through one of these constructors, in its own
# process. Nothing in the app calls them, so R8 would drop them from every service class it keeps.
-keepclassmembers class * implements android.os.IBinder {
    public <init>();
    public <init>(android.content.Context);
}
