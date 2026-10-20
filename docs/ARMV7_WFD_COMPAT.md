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
PLAY / PAUSE / TEARDOWN, paired RTP/RTCP ports and unencrypted RTP/MP2T AVC/AAC
PES delivery into the SDK's existing MediaCodec paths. It advertises 720p30 AVC
and AAC-LC 48kHz stereo, without HEVC, HDCP or raw AV packets. UIBC forwarding is supported
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


## AVC/AAC and playback readiness

The SDK's AAC decoder explicitly expects ADTS framing (`is-adts=1`). The
compatibility receiver therefore preserves complete ADTS frames, advertises only
AAC-LC 48kHz stereo and calls the SDK's audio-format/data callbacks with
STREAM_CAST_MUSIC (16). Five audio tests exercise fragmented/multiple ADTS frames,
unsupported formats and AAC PES selection. The full fake-phone negotiation now
checks audio/video packets and RTCP replies. A callback integration test ensures
PLAY acceptance does not announce an initialized video stream: that notification
waits for actual AVC data. The controller also guards `startCast` while no session
is connected, including after disconnect/re-advertise with a leftover Surface.

The transport advertises normal-car-display parameters alongside WFD capabilities
and logs selected audio/transport. It currently receives UDP MP2T; a peer selecting
TCP/interleaving is rejected explicitly rather than reporting video readiness.
Native audio-clock tuning is not implemented and reports false; decoded audio
buffering remains owned by the SDK. Real-device audio, video, input and long-session
behavior still require validation.
