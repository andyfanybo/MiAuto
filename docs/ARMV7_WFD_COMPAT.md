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
