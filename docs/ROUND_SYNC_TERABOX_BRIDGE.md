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
