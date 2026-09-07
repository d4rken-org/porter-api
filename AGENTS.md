# Porter API

This repository is the API submodule used by Porter and the source of its published client SDK.

Preserve the public Shizuku Java packages, AIDL identifiers and Binder protocol. SDK release versions are independent of protocol versions in `manifest.gradle` and `ShizukuApiConstants`.

The `client` module comes from Porter and uses Apache 2.0 (`client/LICENSE`). Upstream API code retains its MIT license and attribution. Keep the adapter's process-stable backend selection and gated Binder delivery intact.

JitPack publishes only `aidl`, `shared`, `api`, `provider` and `client`. Native/server/demo modules are optional for standalone SDK builds and are included by the Porter app build where needed.

Use the requested Claude review before committing significant changes. Do not publish repositories, tags or external builds before the maintainer's publication step.
