package com.rockchip.gpadc.demo.yolo;

public class InferenceWrapper {

    // 👇 这就是我们丢失的关键“点火”代码：告诉 Java 去加载打包好的 C++ 库
    static {
        System.loadLibrary("rknn4j");
    }

    // 1. 初始化模型
    public native int native_init(int height, int width, int channel, String modelPath);

    // 2. 释放模型
    public native void native_deinit();

    // 3. 执行推理：传入图像 byte 数组，直接返回概率最大的类别索引 (如 0, 1, 2)
    public native int native_run(byte[] in);
}