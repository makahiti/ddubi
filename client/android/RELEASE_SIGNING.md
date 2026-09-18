# DDUBI Android release signing

The permanent keystore is stored outside this repository. Never commit the
keystore or its passwords.

Set these environment variables only in the terminal or CI secret store that
runs a release build:

- `DDUBI_KEYSTORE_PATH`: absolute path to the permanent keystore
- `DDUBI_KEYSTORE_PASSWORD`: keystore password
- `DDUBI_KEY_PASSWORD`: key password
- `DDUBI_KEY_ALIAS`: optional; defaults to `ddubi`

Debug builds do not require these values. Release tasks fail before building
when the required signing values are missing, preventing an APK or AAB from
being accidentally signed with the debug certificate.

On Windows, `client/tool/build_release_apk.ps1` prompts for the shared keystore
and key password without saving it, supplies the variables only to the build
process, and clears them afterward.
