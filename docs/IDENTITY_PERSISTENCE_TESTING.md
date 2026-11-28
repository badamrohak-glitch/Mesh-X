# MESH-X Device Identity Persistence Testing

## 1. Purpose

This document describes how to test the persistence and handling of a MESH-X device identity.

The Android implementation uses:

- A randomly generated UUID as the device ID.
- Private `SharedPreferences` storage for that ID.
- Android Keystore for an EC P-256 (`secp256r1`) key pair.
- A Base64-encoded X.509 SubjectPublicKeyInfo representation of the public key.

The private key is non-exportable and is not returned by the identity bridge command.

## 2. Implementation Details

Main source:

`android-app/android/app/src/main/java/com/meshx/bridge/handlers/IdentityHandler.kt`

Provider:

`AndroidDeviceIdentityProvider`

Preferences:

- File: `meshx_identity`
- Key: `device_id`

Android Keystore:

- Provider: `AndroidKeyStore`
- Alias: `meshx_device_key`
- Algorithm: `EC`
- Curve: `secp256r1`
- Digests: SHA-256 and SHA-512
- User authentication required: `false`

Bridge command:

`GET_IDENTITY`

Expected successful response data:

- `deviceId`
- `publicKey`
- `algorithm`

The private key must never appear in the bridge response.

## 3. Automated Test Coverage

Test source:

`android-app/android/app/src/test/java/com/meshx/bridge/IdentityHandlerTest.kt`

The current tests cover:

### Valid identity response

Checks that:

- `GET_IDENTITY` is registered.
- The bridge response succeeds.
- The device ID is a valid UUID.
- The public key can be decoded.
- The public key is an EC key with a 256-bit curve.
- The algorithm is `EC`.
- No private key is returned.

### Persistence across handler instances

Uses a shared in-memory test provider to verify that repeated requests through separate handler and dispatcher instances return the same identity.

This tests handler behavior and the provider abstraction. It does not independently prove Android Keystore persistence.

### Provider failure

Checks that provider failures become structured bridge errors with the `INTERNAL_ERROR` code.

### Invalid identity fields

Checks that blank identity fields are rejected safely.

## 4. Run Automated Tests

Open PowerShell and run:

```powershell
cd E:\Mesh-X\android-app\android

.\gradlew.bat testDebugUnitTest --no-daemon
```

Expected result:

`BUILD SUCCESSFUL`

Review the Gradle test report if a test fails:

`app/build/reports/tests/testDebugUnitTest/index.html`

## 5. Physical Android Device Test

Install the same development build on one Android device.

Open MESH-X and navigate to the identity information in the application.

If the frontend does not expose the identity details, use the existing native bridge test/debug interface or add a temporary development-only test screen. Do not expose private key material.

### Test A: First identity creation

1. Install and launch MESH-X.
2. Request `GET_IDENTITY`.
3. Record the returned device ID, public key, and algorithm.
4. Confirm that the algorithm is `EC`.
5. Confirm that the private key is not present in the response.

Expected result: a valid device ID and public key are returned.

### Test B: Repeated requests

1. Request `GET_IDENTITY` again.
2. Compare the result with Test A.

Expected result:

- Same device ID.
- Same public key.
- Same algorithm.

### Test C: Application restart

1. Close MESH-X.
2. Launch it again.
3. Request `GET_IDENTITY`.

Expected result: the identity remains unchanged.

### Test D: Device restart

1. Restart the Android device.
2. Launch MESH-X.
3. Request `GET_IDENTITY`.

Expected result: the identity remains available and unchanged.

### Test E: Failure handling

Test a controlled provider failure in an automated test rather than deliberately corrupting the production Keystore.

Expected result: the bridge returns a structured error and does not expose private key material.

## 6. Important Reset and Reinstallation Notes

Do not use these operations during normal persistence tests:

- Clearing MESH-X application storage.
- Uninstalling the application.
- Resetting Android Keystore or the device.

These operations can invalidate the test conditions.

The current implementation stores the device ID in application-private preferences and the key pair in Android Keystore. Do not assume the identity will survive an uninstall, application-data reset, device migration, or backup restoration.

If the preferences are restored without the corresponding Keystore key, the provider may report a missing-key error. Recovery and identity rotation behavior need a separately designed implementation.

## 7. Test Results Record

Record the following for each physical-device test:

| Test | Expected result | Actual result |
|---|---|---|
| First identity creation | Valid UUID and public key | Pending |
| Repeated request | Same identity | Pending |
| Application restart | Same identity | Pending |
| Device restart | Same identity | Pending |
| Provider failure | Structured bridge error | Covered by unit test |
| Private key exposure | No private key returned | Covered by unit test |

Also record:

- Device model
- Android version
- MESH-X build version
- Test date
- Any errors or unexpected behavior

## 8. Security Considerations

- The private key must remain inside Android Keystore.
- Only the public key should be returned by `GET_IDENTITY`.
- Public-key fingerprints are identifiers, not authentication by themselves.
- Peer trust must not be granted solely because a remote device supplied a public key.
- Future peer authentication should prove private-key ownership using a cryptographic challenge-response protocol.
- Identity reset and key-loss recovery must be designed explicitly before production use.

## 9. Completion Criteria

Identity persistence testing is complete when:

- Automated identity tests pass.
- Repeated requests return the same identity.
- Application restart preserves the identity.
- Device restart preserves the identity.
- Provider errors are handled safely.
- Private key material is never returned to the frontend.
- Physical-device test results are recorded.
