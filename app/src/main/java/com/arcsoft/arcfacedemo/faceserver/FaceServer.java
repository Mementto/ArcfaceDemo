package com.arcsoft.arcfacedemo.faceserver;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.util.Log;

import com.arcsoft.arcfacedemo.model.FaceRegisterInfo;
import com.arcsoft.face.ErrorInfo;
import com.arcsoft.face.FaceEngine;
import com.arcsoft.face.FaceFeature;
import com.arcsoft.face.FaceInfo;
import com.arcsoft.face.FaceSimilar;
import com.arcsoft.face.enums.DetectFaceOrientPriority;
import com.arcsoft.face.enums.DetectMode;
import com.arcsoft.imageutil.ArcSoftImageFormat;
import com.arcsoft.imageutil.ArcSoftImageUtil;
import com.arcsoft.imageutil.ArcSoftImageUtilError;
import com.arcsoft.imageutil.ArcSoftRotateDegree;


import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * 人脸库操作类，包含注册和搜索
 */
public class FaceServer {
    private static final String TAG = "FaceServer";
    public static final String IMG_SUFFIX = ".jpg";
    private static FaceEngine faceEngine = null;
    private static FaceServer faceServer = null;
    private static List<FaceRegisterInfo> faceRegisterInfoList;
    public static String ROOT_PATH;
    /**
     * 存放注册图的目录
     */
    public static final String SAVE_IMG_DIR = "register" + File.separator + "imgs";
    /**
     * 存放特征的目录
     */
    private static final String SAVE_FEATURE_DIR = "register" + File.separator + "features";

    /**
     * 是否正在搜索人脸，保证搜索操作单线程进行
     */
    private boolean isProcessing = false;

    public static FaceServer getInstance() {
        if (faceServer == null) {
            synchronized (FaceServer.class) {
                if (faceServer == null) {
                    faceServer = new FaceServer();
                }
            }
        }
        return faceServer;
    }

    /**
     * 初始化
     *
     * @param context 上下文对象
     * @return 是否初始化成功
     */
    public boolean init(Context context) {
        synchronized (this) {
            if (faceEngine == null && context != null) {
                faceEngine = new FaceEngine();
                int engineCode = faceEngine.init(context, DetectMode.ASF_DETECT_MODE_IMAGE, DetectFaceOrientPriority.ASF_OP_0_ONLY, 16, 1, FaceEngine.ASF_FACE_RECOGNITION | FaceEngine.ASF_FACE_DETECT);
                if (engineCode == ErrorInfo.MOK) {
                    initFaceList(context);
                    return true;
                } else {
                    faceEngine = null;
                    Log.e(TAG, "init: failed! code = " + engineCode);
                    return false;
                }
            }
            return false;
        }
    }

    /**
     * 销毁
     */
    public void unInit() {
        synchronized (this) {
            if (faceRegisterInfoList != null) {
                faceRegisterInfoList.clear();
                faceRegisterInfoList = null;
            }
            if (faceEngine != null) {
                faceEngine.unInit();
                faceEngine = null;
            }
        }
    }

    /**
     * 初始化人脸特征数据以及人脸特征数据对应的注册图
     *
     * @param context 上下文对象
     */
    private void initFaceList(Context context) {
        synchronized (this) {
            if (ROOT_PATH == null) {
                ROOT_PATH = context.getFilesDir().getAbsolutePath();
            }
            File featureDir = new File(ROOT_PATH + File.separator + SAVE_FEATURE_DIR);
            if (!featureDir.exists() || !featureDir.isDirectory()) {
                return;
            }
            File[] featureFiles = featureDir.listFiles();
            if (featureFiles == null || featureFiles.length == 0) {
                return;
            }
            faceRegisterInfoList = new ArrayList<>();
            for (File featureFile : featureFiles) {
                try {
                    FileInputStream fis = new FileInputStream(featureFile);
                    byte[] feature = new byte[FaceFeature.FEATURE_SIZE];
                    fis.read(feature);
                    fis.close();
                    faceRegisterInfoList.add(new FaceRegisterInfo(feature, featureFile.getName()));
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }
    }

    /**
     * 用于预览时注册人脸
     *
     * @param context  上下文对象
     * @param nv21     NV21数据
     * @param width    NV21宽度
     * @param height   NV21高度
     * @param faceInfo {@link FaceEngine#detectFaces(byte[], int, int, int, List)}获取的人脸信息
     * @param name     保存的名字，若为空则使用时间戳
     * @return 是否注册成功
     */
    public boolean registerNv21(Context context, byte[] nv21, int width, int height, FaceInfo faceInfo, String name) {
        synchronized (this) {
            if (faceEngine == null || context == null || nv21 == null || width % 4 != 0 || nv21.length != width * height * 3 / 2) {
                Log.e(TAG, "registerNv21: invalid params");
                return false;
            }

            if (ROOT_PATH == null) {
                ROOT_PATH = context.getFilesDir().getAbsolutePath();
            }
            //特征存储的文件夹
            File featureDir = new File(ROOT_PATH + File.separator + SAVE_FEATURE_DIR);
            if (!featureDir.exists() && !featureDir.mkdirs()) {
                Log.e(TAG, "registerNv21: can not create feature directory");
                return false;
            }
            //图片存储的文件夹
            File imgDir = new File(ROOT_PATH + File.separator + SAVE_IMG_DIR);
            if (!imgDir.exists() && !imgDir.mkdirs()) {
                Log.e(TAG, "registerNv21: can not create image directory");
                return false;
            }
            FaceFeature faceFeature = new FaceFeature();
            //特征提取
            int code = faceEngine.extractFaceFeature(nv21, width, height, FaceEngine.CP_PAF_NV21, faceInfo, faceFeature);
            if (code != ErrorInfo.MOK) {
                Log.e(TAG, "registerNv21: extractFaceFeature failed , code is " + code);
                return false;
            } else {

                String userName = name == null ? String.valueOf(System.currentTimeMillis()) : name;
                try {
                    // 保存注册结果（注册图、特征数据）
                    // 为了美观，扩大rect截取注册图
                    Rect cropRect = getBestRect(width, height, faceInfo.getRect());
                    if (cropRect == null) {
                        Log.e(TAG, "registerNv21: cropRect is null!");
                        return false;
                    }

                    cropRect.left &= ~3;
                    cropRect.top &= ~3;
                    cropRect.right &= ~3;
                    cropRect.bottom &= ~3;

                    File file = new File(imgDir + File.separator + userName + IMG_SUFFIX);


                    // 创建一个头像的Bitmap，存放旋转结果图
                    Bitmap headBmp = getHeadImage(nv21, width, height, faceInfo.getOrient(), cropRect, ArcSoftImageFormat.NV21);

                    FileOutputStream fosImage = new FileOutputStream(file);
                    headBmp.compress(Bitmap.CompressFormat.JPEG, 100, fosImage);
                    fosImage.close();


                    FileOutputStream fosFeature = new FileOutputStream(featureDir + File.separator + userName);
                    fosFeature.write(faceFeature.getFeatureData());
                    fosFeature.close();

                    //内存中的数据同步
                    if (faceRegisterInfoList == null) {
                        faceRegisterInfoList = new ArrayList<>();
                    }
                    faceRegisterInfoList.add(new FaceRegisterInfo(faceFeature.getFeatureData(), userName));
                    return true;
                } catch (IOException e) {
                    e.printStackTrace();
                    return false;
                }
            }
        }

    }

    /**
     * 截取合适的头像并旋转，保存为注册头像
     *
     * @param originImageData 原始的BGR24数据
     * @param width           BGR24图像宽度
     * @param height          BGR24图像高度
     * @param orient          人脸角度
     * @param cropRect        裁剪的位置
     * @param imageFormat     图像格式
     * @return 头像的图像数据
     */
    private Bitmap getHeadImage(byte[] originImageData, int width, int height, int orient, Rect cropRect, ArcSoftImageFormat imageFormat) {
        byte[] headImageData = ArcSoftImageUtil.createImageData(cropRect.width(), cropRect.height(), imageFormat);
        int cropCode = ArcSoftImageUtil.cropImage(originImageData, headImageData, width, height, cropRect, imageFormat);
        if (cropCode != ArcSoftImageUtilError.CODE_SUCCESS) {
            throw new RuntimeException("crop image failed, code is " + cropCode);
        }

        //判断人脸旋转角度，若不为0度则旋转注册图
        byte[] rotateHeadImageData = null;
        int rotateCode;
        int cropImageWidth;
        int cropImageHeight;
        // 90度或270度的情况，需要宽高互换
        if (orient == FaceEngine.ASF_OC_90 || orient == FaceEngine.ASF_OC_270) {
            cropImageWidth = cropRect.height();
            cropImageHeight = cropRect.width();
        } else {
            cropImageWidth = cropRect.width();
            cropImageHeight = cropRect.height();
        }
        ArcSoftRotateDegree rotateDegree = null;
        switch (orient) {
            case FaceEngine.ASF_OC_90:
                rotateDegree = ArcSoftRotateDegree.DEGREE_270;
                break;
            case FaceEngine.ASF_OC_180:
                rotateDegree = ArcSoftRotateDegree.DEGREE_180;
                break;
            case FaceEngine.ASF_OC_270:
                rotateDegree = ArcSoftRotateDegree.DEGREE_90;
                break;
            case FaceEngine.ASF_OC_0:
            default:
                rotateHeadImageData = headImageData;
                break;
        }
        // 非0度的情况，旋转图像
        if (rotateDegree != null){
            rotateHeadImageData = new byte[headImageData.length];
            rotateCode = ArcSoftImageUtil.rotateImage(headImageData, rotateHeadImageData, cropRect.width(), cropRect.height(), rotateDegree, imageFormat);
            if (rotateCode != ArcSoftImageUtilError.CODE_SUCCESS) {
                throw new RuntimeException("rotate image failed, code is " + rotateCode);
            }
        }
        // 将创建一个Bitmap，并将图像数据存放到Bitmap中
        Bitmap headBmp = Bitmap.createBitmap(cropImageWidth, cropImageHeight, Bitmap.Config.RGB_565);
        if (ArcSoftImageUtil.imageDataToBitmap(rotateHeadImageData, headBmp, imageFormat) != ArcSoftImageUtilError.CODE_SUCCESS) {
            throw new RuntimeException("failed to transform image data to bitmap");
        }
        return headBmp;
    }

    /**
     * 在特征库中搜索
     *
     * @param faceFeature 传入特征数据
     * @return 比对结果
     */
    public CompareResult getTopOfFaceLib(FaceFeature faceFeature) {
        if (faceEngine == null || isProcessing || faceFeature == null || faceRegisterInfoList == null || faceRegisterInfoList.size() == 0) {
            return null;
        }
        FaceFeature tempFaceFeature = new FaceFeature();
        FaceSimilar faceSimilar = new FaceSimilar();
        float maxSimilar = 0;
        int maxSimilarIndex = -1;
        isProcessing = true;
        for (int i = 0; i < faceRegisterInfoList.size(); i++) {
            tempFaceFeature.setFeatureData(faceRegisterInfoList.get(i).getFeatureData());
            faceEngine.compareFaceFeature(faceFeature, tempFaceFeature, faceSimilar);
            if (faceSimilar.getScore() > maxSimilar) {
                maxSimilar = faceSimilar.getScore();
                maxSimilarIndex = i;
            }
        }
        isProcessing = false;
        if (maxSimilarIndex != -1) {
            return new CompareResult(faceRegisterInfoList.get(maxSimilarIndex).getName(), maxSimilar);
        }
        return null;
    }

    /**
     * 将图像中需要截取的Rect向外扩张一倍，若扩张一倍会溢出，则扩张到边界，若Rect已溢出，则收缩到边界
     *
     * @param width   图像宽度
     * @param height  图像高度
     * @param srcRect 原Rect
     * @return 调整后的Rect
     */
    private static Rect getBestRect(int width, int height, Rect srcRect) {
        if (srcRect == null) {
            return null;
        }
        Rect rect = new Rect(srcRect);

        // 原rect边界已溢出宽高的情况
        int maxOverFlow = Math.max(-rect.left, Math.max(-rect.top, Math.max(rect.right - width, rect.bottom - height)));
        if (maxOverFlow >= 0) {
            rect.inset(maxOverFlow, maxOverFlow);
            return rect;
        }

        // 原rect边界未溢出宽高的情况
        int padding = rect.height() / 2;

        // 若以此padding扩张rect会溢出，取最大padding为四个边距的最小值
        if (!(rect.left - padding > 0 && rect.right + padding < width && rect.top - padding > 0 && rect.bottom + padding < height)) {
            padding = Math.min(Math.min(Math.min(rect.left, width - rect.right), height - rect.bottom), rect.top);
        }
        rect.inset(-padding, -padding);
        return rect;
    }
}
