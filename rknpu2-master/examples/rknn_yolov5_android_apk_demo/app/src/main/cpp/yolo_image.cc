#include "yolo_image.h"
#include "rknn_api.h"
#include <math.h>

// --- 核心修复魔法：屏蔽系统宏，伪造句柄，绕过 gralloc.h 报错 ---
#undef ANDROID
#undef __ANDROID__
#define buffer_handle_t void*
#include "rga/RgaApi.h"
#undef buffer_handle_t
#define ANDROID 1
#define __ANDROID__ 1
// -------------------------------------------------------------

#include <stdio.h>
#include <stdlib.h>
#include <string.h>

static rknn_context ctx;
static int img_width = 0;
static int img_height = 0;
static int img_channel = 0;
static int out_size = 0;

static unsigned char *load_file(const char *filename, int *model_size) {
    FILE *fp = fopen(filename, "rb");
    if (fp == NULL) return NULL;
    fseek(fp, 0, SEEK_END);
    int size = ftell(fp);
    fseek(fp, 0, SEEK_SET);
    unsigned char *data = (unsigned char *)malloc(size);
    fread(data, 1, size, fp);
    fclose(fp);
    *model_size = size;
    return data;
}

int create(int height, int width, int channel, char *model_path) {
    img_height = height;
    img_width = width;
    img_channel = channel;

    int model_size;
    unsigned char *model_data = load_file(model_path, &model_size);
    if (model_data == NULL) return -1;

    int ret = rknn_init(&ctx, model_data, model_size, 0, NULL);
    free(model_data);
    if (ret < 0) return -1;

    rknn_tensor_attr output_attr;
    output_attr.index = 0;
    rknn_query(ctx, RKNN_QUERY_OUTPUT_ATTR, &output_attr, sizeof(output_attr));
    out_size = output_attr.n_elems;

    return 0;
}

// =======================================================
// 核心修改区：手动将图像数据归一化（除以 255）
// =======================================================
int run_classification(char *inData) {
    int pixel_count = img_width * img_height * img_channel;

    // 1. 申请一块新的内存，用来存放小数 (float)
    float* float_input = (float*)malloc(pixel_count * sizeof(float));

    // 2. 强行归一化：把 0~255 的大整数，除以 255 变成 0.0~1.0 的小数
    unsigned char* uData = (unsigned char*)inData;
    for (int i = 0; i < pixel_count; i++) {
        // 如果你的模型用了标准的 ImageNet 归一化，请告诉我，这里先用最通用的 /255.0
        float_input[i] = uData[i] / 255.0f;
    }

    rknn_input inputs[1];
    memset(inputs, 0, sizeof(inputs));
    inputs[0].index = 0;
    inputs[0].type = RKNN_TENSOR_FLOAT32; // 【关键】告诉 NPU 我们喂的是小数 (Float)！
    inputs[0].size = pixel_count * sizeof(float);
    inputs[0].fmt = RKNN_TENSOR_NHWC;
    inputs[0].buf = float_input;

    rknn_inputs_set(ctx, 1, inputs);
    rknn_run(ctx, NULL);

    rknn_output outputs[1];
    memset(outputs, 0, sizeof(outputs));
    outputs[0].want_float = 1;
    rknn_outputs_get(ctx, 1, outputs, NULL);

    float *logits = (float *)outputs[0].buf;

    // Softmax 真实概率计算
    float max_logit = -999999.0f;
    for (int i = 0; i < out_size; i++) {
        if (logits[i] > max_logit) max_logit = logits[i];
    }

    float sum_exp = 0.0f;
    float* probs = (float*)malloc(out_size * sizeof(float));
    for (int i = 0; i < out_size; i++) {
        probs[i] = exp(logits[i] - max_logit);
        sum_exp += probs[i];
    }

    int max_index = 0;
    float max_prob = -1.0f;
    for (int i = 0; i < out_size; i++) {
        probs[i] = probs[i] / sum_exp;
        if (probs[i] > max_prob) {
            max_prob = probs[i];
            max_index = i;
        }
    }

    // 打印到日志
    LOGI("AI诊断预测 - 分类ID: %d, 真实概率: %f", max_index, max_prob);

    // 释放内存（非常重要）
    free(probs);
    free(float_input);

    // 置信度拦截 (低于 60% 视为未知物体)
    if (max_prob < 0.60f) {
        rknn_outputs_release(ctx, 1, outputs);
        return -1;
    }

    rknn_outputs_release(ctx, 1, outputs);
    return max_index;
}

void destroy() {
    rknn_destroy(ctx);
}

int colorConvertAndFlip(void *src, int srcFmt, void *dst, int dstFmt, int width, int height, int flip) {
    int ret = 0;
    rga_info_t src_info;
    rga_info_t dst_info;
    memset(&src_info, 0, sizeof(rga_info_t));
    memset(&dst_info, 0, sizeof(rga_info_t));

    src_info.fd = -1;
    src_info.mmuFlag = 1;
    src_info.virAddr = src;
    src_info.format = srcFmt;
    src_info.rect.xoffset = 0;
    src_info.rect.yoffset = 0;
    src_info.rect.width = width;
    src_info.rect.height = height;

    dst_info.fd = -1;
    dst_info.mmuFlag = 1;
    dst_info.virAddr = dst;
    dst_info.format = dstFmt;
    dst_info.rect.xoffset = 0;
    dst_info.rect.yoffset = 0;
    dst_info.rect.width = width;
    dst_info.rect.height = height;

    if (flip != -1) {
        src_info.rotation = flip;
    }

    ret = c_RkRgaBlit(&src_info, &dst_info, NULL);
    return ret;
}