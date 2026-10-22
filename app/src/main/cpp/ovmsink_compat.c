// ARMv7 JNI bridge to MiAuto's unencrypted AVC Wi-Fi Display receiver.
// This is not the vendor implementation and never reports a dummy video stream.
#include <jni.h>

static jclass backend(JNIEnv *env) {
    return (*env)->FindClass(env, "com/miauto/app/wfd/CompatWfdSink");
}
JNIEXPORT jboolean JNICALL Java_com_ucarsink_sink_natives_SinkNative_start(
        JNIEnv *env, jclass cls, jstring host, jint port, jint width, jint height,
        jint dpi, jint fps, jboolean hevc, jboolean fusion, jboolean raw) {
    (void)cls;
    jclass type = backend(env);
    if (!type) return JNI_FALSE;
    jmethodID method = (*env)->GetStaticMethodID(env, type, "start", "(Ljava/lang/String;IIIIIZZZ)Z");
    jboolean result = method ? (*env)->CallStaticBooleanMethod(env, type, method, host, port, width, height, dpi, fps, hevc, fusion, raw) : JNI_FALSE;
    (*env)->DeleteLocalRef(env, type);
    return result;
}
static jboolean command(JNIEnv *env, jstring id, const char *name) {
    jclass type = backend(env);
    if (!type) return JNI_FALSE;
    jmethodID method = (*env)->GetStaticMethodID(env, type, name, "(Ljava/lang/String;)Z");
    jboolean result = method ? (*env)->CallStaticBooleanMethod(env, type, method, id) : JNI_FALSE;
    (*env)->DeleteLocalRef(env, type);
    return result;
}
JNIEXPORT jboolean JNICALL Java_com_ucarsink_sink_natives_SinkNative_stop(JNIEnv *env, jclass cls, jstring id) {
    (void)cls; return command(env, id, "stop");
}
JNIEXPORT jboolean JNICALL Java_com_ucarsink_sink_natives_SinkNative_pause(JNIEnv *env, jclass cls, jstring id) {
    (void)cls; return command(env, id, "pause");
}
JNIEXPORT jboolean JNICALL Java_com_ucarsink_sink_natives_SinkNative_resume(JNIEnv *env, jclass cls, jstring id) {
    (void)cls; return command(env, id, "resume");
}
JNIEXPORT jboolean JNICALL Java_com_ucarsink_sink_natives_SinkNative_setParameter(JNIEnv *env, jclass cls, jstring id, jobject values) {
    (void)cls;
    jclass type = backend(env);
    if (!type) return JNI_FALSE;
    jmethodID method = (*env)->GetStaticMethodID(env, type, "parameter", "(Ljava/lang/String;Ljava/util/HashMap;)Z");
    jboolean result = method ? (*env)->CallStaticBooleanMethod(env, type, method, id, values) : JNI_FALSE;
    (*env)->DeleteLocalRef(env, type);
    return result;
}
JNIEXPORT jboolean JNICALL Java_com_ucarsink_sink_natives_SinkNative_onUibcEvent(JNIEnv *env, jclass cls, jstring id, jbyteArray bytes) {
    (void)cls;
    jclass type = backend(env);
    if (!type) return JNI_FALSE;
    jmethodID method = (*env)->GetStaticMethodID(env, type, "input", "(Ljava/lang/String;[B)Z");
    jboolean result = method ? (*env)->CallStaticBooleanMethod(env, type, method, id, bytes) : JNI_FALSE;
    (*env)->DeleteLocalRef(env, type);
    return result;
}
