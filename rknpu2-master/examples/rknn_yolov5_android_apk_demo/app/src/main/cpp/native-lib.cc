#include <jni.h>
#include <string.h>
#include "yolo_image.h"
#include "rga/rga.h"

// 字符串转换辅助函数
static char* jstringToChar(JNIEnv* env, jstring jstr) {
    char* rtn = NULL;
    jclass clsstring = env->FindClass("java/lang/String");
    jstring strencode = env->NewStringUTF("utf-8");
    jmethodID mid = env->GetMethodID(clsstring, "getBytes", "(Ljava/lang/String;)[B");
    jbyteArray barr = (jbyteArray) env->CallObjectMethod(jstr, mid, strencode);
    jsize alen = env->GetArrayLength(barr);
    jbyte* ba = env->GetByteArrayElements(barr, JNI_FALSE);
    if (alen > 0) {
        rtn = new char[alen + 1];
        memcpy(rtn, ba, alen);
        rtn[alen] = 0;
    }
    env->ReleaseByteArrayElements(barr, ba, 0);
    return rtn;
}

// 1. 初始化模型
extern "C" JNIEXPORT jint JNICALL Java_com_rockchip_gpadc_demo_yolo_InferenceWrapper_native_1init(JNIEnv *env, jobject obj, jint im_height, jint im_width, jint im_channel, jstring model_path) {
    char *model_path_p = jstringToChar(env, model_path);
    return create(im_height, im_width, im_channel, model_path_p);
}

// 2. 销毁模型
extern "C" JNIEXPORT void JNICALL Java_com_rockchip_gpadc_demo_yolo_InferenceWrapper_native_1deinit(JNIEnv *env, jobject obj) {
    destroy();
}

// 3. 执行分类推理
extern "C" JNIEXPORT jint JNICALL Java_com_rockchip_gpadc_demo_yolo_InferenceWrapper_native_1run(JNIEnv *env, jobject obj, jbyteArray in) {
    jboolean inputCopy = JNI_FALSE;
    jbyte* const inData = env->GetByteArrayElements(in, &inputCopy);
    int class_id = run_classification((char *)inData);
    env->ReleaseByteArrayElements(in, inData, JNI_ABORT);
    return class_id;
}

// 4. 摄像头图像格式转换 (必须保留，用于底层的 rga 硬件加速转换)
extern "C" JNIEXPORT jint JNICALL Java_com_rockchip_gpadc_demo_rga_RGA_color_1convert_1and_1flip(JNIEnv *env, jclass clazz, jbyteArray src, jint src_fmt, jbyteArray dst, jint dst_fmt, jint width, jint height, jint flip) {
    jboolean copy = JNI_FALSE;
    jbyte* src_buf = env->GetByteArrayElements(src, &copy);
    jbyte* dst_buf = env->GetByteArrayElements(dst, &copy);
    jint ret = colorConvertAndFlip(src_buf, src_fmt, dst_buf, dst_fmt, width, height, flip);
    env->ReleaseByteArrayElements(src, src_buf, 0);
    env->ReleaseByteArrayElements(dst, dst_buf, 0);
    return ret;
}