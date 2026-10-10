# Round Sync / TeraBox integration (Android)

## Direct cloud mode (preferred when supported)

1. In the TeraBox-capable Round Sync build, confirm that the remote can list a small artwork folder.
2. In Round Sync: **Settings > File Access > Enable Content Provider Preview**. This feature is experimental.
3. In Asterion Core: **Automation > TeraBox via Round Sync > Choose library folder**.
4. In Android's document picker, choose **Round Sync**, enter the configured **TeraBox** remote, select an artwork subfolder and grant read/write tree access.
5. Scan a small folder first. Test reading a file, a same-folder rename, a cross-folder move and a copy before enabling library-scale automation.

Asterion already supports Android SAF. It never reads Round Sync's private rclone credentials.
In direct mode image reads and compatible file operations use the Round Sync content provider.
For Round Sync VCP (.vcp) cross-folder move/copy, Asterion now requires a provider-native operation.
It *does not* silently copy streams then delete the source: when unsupported, the operation fails, leaving the original.
This is intentional to reduce risks documented for the experimental content provider.

Known limitations:
- Not all Round Sync builds expose or allow selecting complete writable document trees.
- Remote provider operations may be slow or incomplete; offline use and partial uploads are not guaranteed.
- Android only grants access to the selected subtree; do not enable Round Sync's global 'Allow any app to access your remotes' setting.
- If the provider fails to grant read and write SAF permissions, Asterion cannot run direct modification safely.

## Fallback: non-destructive Round Sync jobs

When direct provider access is unavailable:
1. Configure **Copy TeraBox -> local mirror** and **Copy local mirror -> TeraBox** as **separate Round Sync tasks**. Do not choose sync/delete for initial testing.
2. Select the local mirror as Asterion's library via SAF.
3. On Asterion's Automation screen, set the Round Sync application package and the two integer task IDs copied from Round Sync's task menu.
4. Pull to the mirror; confirm the Round Sync task actually finishes, then scan/automate inside Asterion.
5. Publish explicitly, or enable optional publish-after-successful-Asterion-automation.

The bridge uses Round Sync's documented START_TASK service, *only when that service is exported*.
Some Round Sync versions disable external task launch. If unavailable, the buttons provide a clear error and the task must be run manually in Round Sync.
A START_TASK intent is a **request**, not a transfer-complete signal; confirm completion in Round Sync.

## Safety and compatibility

- No TeraBox account tokens, passwords, or rclone.conf are copied into Asterion.
- Neither mode runs cloud deletion automatically as part of connection setup.
- Pre-existing local files, model installations, Fusion state, and prior local changes are not migrated or discarded by the bridge.
- All external cloud writes must still be validated with the exact Round Sync variant on the physical phone.
- Tested locally: Android app build and focused memory regression tests. Physical Round Sync remote-to-cloud operations are **not yet verified** because the phone was offline from ADB at build time.

References:
- https://github.com/newhinton/Round-Sync
- https://github.com/newhinton/Round-Sync/issues/28
- https://github.com/newhinton/Round-Sync/issues/184
- https://github.com/newhinton/Round-Sync/issues/302
