#include <jni.h>
#include <stdint.h>
#include <brotli/decode.h>
#include <brotli/encode.h>

#define MAX_TEXT_BYTES 4096
#define QUALITY 11

static jbyteArray invalid(JNIEnv *env, const char *message) {
    jclass type = (*env)->FindClass(env, "java/lang/IllegalArgumentException");
    if (type != NULL) (*env)->ThrowNew(env, type, message);
    return NULL;
}

static jbyteArray result(JNIEnv *env, const uint8_t *bytes, size_t size) {
    jbyteArray output = (*env)->NewByteArray(env, (jsize)size);
    if (output != NULL) (*env)->SetByteArrayRegion(env, output, 0, (jsize)size, (const jbyte *)bytes);
    return output;
}

JNIEXPORT jbyteArray JNICALL
Java_org_meshtastic_core_brotli_BrotliNative_compress(JNIEnv *env, jobject self, jbyteArray plain) {
    if (plain == NULL) return invalid(env, "Missing VIP text");
    jsize size = (*env)->GetArrayLength(env, plain);
    if (size > MAX_TEXT_BYTES) return invalid(env, "VIP text exceeds the size limit");
    uint8_t input[MAX_TEXT_BYTES];
    uint8_t output[MAX_TEXT_BYTES * 2];
    (*env)->GetByteArrayRegion(env, plain, 0, size, (jbyte *)input);
    if ((*env)->ExceptionCheck(env)) return NULL;
    size_t output_size = sizeof(output);
    if (!BrotliEncoderCompress(QUALITY, 16, BROTLI_MODE_GENERIC, (size_t)size, input, &output_size, output)) {
        return invalid(env, "VIP Brotli compression failed");
    }
    return result(env, output, output_size);
}

JNIEXPORT jbyteArray JNICALL
Java_org_meshtastic_core_brotli_BrotliNative_decompress(JNIEnv *env, jobject self, jbyteArray compressed) {
    if (compressed == NULL) return invalid(env, "Missing VIP Brotli payload");
    jsize size = (*env)->GetArrayLength(env, compressed);
    if (size > MAX_TEXT_BYTES) return invalid(env, "VIP Brotli payload exceeds the size limit");
    uint8_t input[MAX_TEXT_BYTES];
    uint8_t output[MAX_TEXT_BYTES + 1];
    (*env)->GetByteArrayRegion(env, compressed, 0, size, (jbyte *)input);
    if ((*env)->ExceptionCheck(env)) return NULL;
    BrotliDecoderState *state = BrotliDecoderCreateInstance(NULL, NULL, NULL);
    if (state == NULL) return invalid(env, "VIP Brotli decoder allocation failed");
    size_t available_in = (size_t)size;
    size_t available_out = sizeof(output);
    const uint8_t *next_in = input;
    uint8_t *next_out = output;
    size_t total_out = 0;
    BrotliDecoderResult decoded =
        BrotliDecoderDecompressStream(state, &available_in, &next_in, &available_out, &next_out, &total_out);
    BrotliDecoderDestroyInstance(state);
    if (decoded != BROTLI_DECODER_RESULT_SUCCESS || available_in != 0 || total_out > MAX_TEXT_BYTES) {
        return invalid(env, "Invalid, truncated or oversized VIP Brotli message");
    }
    return result(env, output, total_out);
}
