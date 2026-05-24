/**
 * @ClassName yolo_image (现在实际用于分类模型)
 */

#ifndef RK_YOLOV5_DEMO_YOLO_IMAGE_H
#define RK_YOLOV5_DEMO_YOLO_IMAGE_H

#include <android/log.h>

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "rkyolo4j", ##__VA_ARGS__);
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "rkyolo4j", ##__VA_ARGS__);

// 1. 初始化和销毁
int create(int im_height, int im_width, int im_channel, char *model_path);
void destroy();

// 2. 咱们刚才替换的分类模型核心运行函数
int run_classification(char *inData);

// 3. 摄像头图像格式转换（必须保留）
int colorConvertAndFlip(void *src, int srcFmt, void *dst, int dstFmt, int width, int height, int flip);

#endif //RK_YOLOV5_DEMO_YOLO_IMAGE_H