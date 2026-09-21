# Porter API

This repository is the API submodule used by Porter and the source of its published client SDK.

Preserve the AIDL identifiers, the Binder protocol and every wire-level string; the Java packages of Porter's own code are free to change, and everything Porter owns is Kotlin. SDK release versions are independent of the protocol versions in `PorterProtocol` and `ShizukuApiConstants`.

Upstream API code retains its MIT license and attribution. Keep the adapter's process-stable backend selection and gated Binder delivery intact.

JitPack publishes `protocol`, `sdk`, `sdk-extras` and `shizuku-compat`, which is what `jitpack.yml` builds and what `publishedModules` in `build.gradle.kts` allows. `sdk-extras` is optional convenience on top of `sdk` and depends on it. `shizuku-compat` holds only the upstream `moe.shizuku.api.BinderContainer` and is opt-in, because it collides with `dev.rikka.shizuku:provider`. The Shizuku-compatible `aidl` and `shared` modules are not published; the Porter app build consumes them in-tree. `porsh` and `server-shared` are optional for standalone SDK builds (`-PincludeShell=true`).

Use the requested Claude review before committing significant changes. Do not publish repositories, tags or external builds before the maintainer's publication step.

Keep agent context under `.claude/`; do not add top-level AGENTS files or directories. Use only DebugBadger or ADB for device interaction. Never control the host mouse or keyboard.
