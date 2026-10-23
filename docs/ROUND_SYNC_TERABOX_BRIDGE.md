# TeraBox files through Round Sync

## Browse any remote file

1. Install a Round Sync variant that supports TeraBox and configure its TeraBox remote.
2. Confirm you can browse your files inside Round Sync.
3. In Round Sync, enable **Settings > File Access > Content Provider Preview** (experimental).
4. In AsterionCore, use **Library > Browse TeraBox / Round Sync files** or **Automation > Browse / open cloud file**.
5. In Android's document picker, select **Round Sync**, navigate to the TeraBox remote and choose a file.

Asterion passes the selected document URI to a compatible Android viewer; it does not
copy the entire TeraBox account, import a non-image file into its artwork database,
or access Round Sync credentials. Supports other document types exposed by the provider.

## Index artwork remotely

In **Automation > TeraBox via Round Sync**, select **Connect cloud artwork folder to library**.
Select the TeraBox tree from Round Sync in Android's folder picker, when that provider
supports persistent read/write tree grants. This uses Asterion's existing SAF image
scanning and management. If the provider refuses the tree grant, Asterion retains
your old library settings; individual cloud files can still be opened.

Round Sync's document-provider feature is experimental. A TeraBox-capable variant
and an installed viewer are necessary. Full remote browsing and file compatibility
require real-device verification. Cloud deletion and synchronization are not initiated
by this connection.

## Build

The GitHub Actions workflow produces `AsterionCore-debug-apk` as a downloadable
artifact, containing `app-debug.apk`. CI builds use Android's debug signing key;
the APK might not update a differently signed installed release. Do not uninstall
an existing application without a backup of its private data.

## Model-import performance and reliability (v2.0.4)

AsterionCore now copies each selected Round Sync cloud file once to application-owned
staging while measuring byte progress and calculating SHA-256 during the transfer.
A staged model directory is renamed into the permanent installation directory
when supported by the filesystem, rather than redundantly copying its contents.
Single-file ONNX/TFLite/GGUF imports are persisted under model-packages before
the temporary staging area is removed (previously this could invalidate the
registered model path). Model imports are serialized to avoid concurrent duplicate
cloud transfers. A truncated transfer with a known provider-declared size is
rejected, and incomplete staged data is cleaned up.

Round Sync and TeraBox still determine remote transfer throughput. The app cannot
guarantee bandwidth or seek/resume support for the provider's content URI.
