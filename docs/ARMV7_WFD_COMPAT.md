# ARMv7 H.264 WFD compatibility receiver (experimental)

The `armv7-ffmpeg-libs` branch adds FFmpeg HEVC decoder binaries, and
`fix/32-bit-arm-bundled-decoder` handles their availability in the old UI.
Neither branch includes ARMv7 `libovmsink.so`. Runtime SDK v1.5.3 does reference
that library: `notifyAudioLatencyChanged` calls `SinkNative.setParameter`
before decoding starts, and `startWfdServer` calls `SinkNative.start` for AVC too.
The vendor arm64 library depends on log/m/dl/c, not avcodec/avutil/swscale.

For armeabi-v7a only, CMake now builds our own small `libovmsink.so` JNI bridge
when no vendor library is supplied. It forwards the existing SDK native ABI to
an application-owned RTSP/RTP receiver. Other ABIs keep existing behavior.
The receiver implements WFD OPTIONS / GET_PARAMETER / SET_PARAMETER / SETUP /
PLAY / PAUSE / TEARDOWN and unencrypted RTP/MP2T AVC PES delivery into the SDK's
existing MediaCodec path. It advertises 720p30 AVC and does not advertise HEVC,
HDCP, raw AV packets, or unsupported cast audio. UIBC forwarding is supported
when the peer negotiates an unencrypted generic input TCP port.

This is an independent compatibility implementation, not a rebuilt vendor SDK.
No vendor implementation code or proprietary SDK dex is copied into the repo.
Protocol reference: AOSP `frameworks/av/media/libstagefright/wifi-display/sink/WifiDisplaySink.cpp`
(https://android.googlesource.com/platform/frameworks/av/+/8060060/media/libstagefright/wifi-display/sink/WifiDisplaySink.cpp).

`WfdProtocolTest` exercises bounded TCP framing, malformed data rejection,
AVC PES/PTS extraction, and a complete fake-phone RTSP/RTP negotiation.
`scripts/test_ovmsink.sh` compiles the real JNI bridge on a host JVM against a
signature-only fixture and checks native loading, pre-session latency updates,
unsupported-mode rejection, connection, stop and the callback ABI.
CI also rejects APKs without the expected ARM32/ARM64 sink ELF architecture.

Device projection validation is pending. Passing JNI or negotiation tests does
not establish working video, audio, input, reconnect, or long-session stability.

## SDK generated resource relocation

The extracted v1.5.3 SDK reads `com.ucar.sdk.R.drawable.ucar` with its donor APK's
numeric resource ID. In this APK that ID resolves to a non-bitmap resource.
`BitmapUtil.decodeResource` returns null and then crashes inside `compress`.
Although the observer catches the exception, the rest of `receivedClientHello`
is skipped, including updating the phone protocol version from 1 to 1793.
A live debugger test confirmed that binding the existing bitmap launcher
foreground removes this exception and restores the correct protocol version.
It did not by itself resolve the remaining no-video/heartbeat-timeout symptom.

`rebind_iccoa_resources.py` changes only that generated four-byte integer and the
DEX signature/checksum, using the current variant's AGP R.txt. It leaves all SDK
instructions, size and the original private file unchanged. Injection normalizes
this field/checksum when deduplicating previous SDK copies. Six independent
fixture/CLI tests check relocation, checksum integrity, idempotence, malformed
input rejection and preservation of the original SDK. The resulting SDK binary
remains private and is not committed to the repository.
