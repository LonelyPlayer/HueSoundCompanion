package app.huebeat;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.RectF;

import com.google.mediapipe.framework.image.BitmapImageBuilder;
import com.google.mediapipe.framework.image.MPImage;
import com.google.mediapipe.tasks.components.containers.Category;
import com.google.mediapipe.tasks.components.containers.Detection;
import com.google.mediapipe.tasks.core.BaseOptions;
import com.google.mediapipe.tasks.core.Delegate;
import com.google.mediapipe.tasks.vision.core.RunningMode;
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetector;
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetectorResult;

import java.io.InputStream;
import java.util.Collections;

/**
 * Finds TVs in camera frames with Google's MediaPipe object detector (EfficientDet-Lite0, trained
 * on COCO, which has a "tv" class). Runs on the GPU when possible, otherwise on the CPU.
 */
final class TvMl {
    static final String MODEL = "efficientdet_lite0.tflite";
    private ObjectDetector detector;
    String error;
    boolean onGpu;

    boolean init(Context c) {
        try (InputStream in = c.getAssets().open(MODEL)) {
            // just checking the model is bundled
        } catch (Exception e) {
            error = "detection model missing";
            return false;
        }
        for (Delegate d : new Delegate[]{Delegate.GPU, Delegate.CPU}) {
            try {
                BaseOptions base = BaseOptions.builder().setModelAssetPath(MODEL).setDelegate(d).build();
                ObjectDetector.ObjectDetectorOptions opts = ObjectDetector.ObjectDetectorOptions.builder()
                        .setBaseOptions(base)
                        .setRunningMode(RunningMode.IMAGE)
                        .setScoreThreshold(0.2f)
                        .setMaxResults(3)
                        .setCategoryAllowlist(Collections.singletonList("tv"))
                        .build();
                detector = ObjectDetector.createFromOptions(c, opts);
                onGpu = d == Delegate.GPU;
                return true;
            } catch (Throwable t) {
                error = t.getMessage();
            }
        }
        return false;
    }

    /** @return {x0, y0, x1, y1, score} of the most likely TV, in bitmap pixels, or null */
    float[] detect(Bitmap bmp) {
        if (detector == null) return null;
        try {
            MPImage img = new BitmapImageBuilder(bmp).build();
            ObjectDetectorResult res = detector.detect(img);
            float[] best = null;
            for (Detection d : res.detections()) {
                float score = 0f;
                for (Category cat : d.categories()) score = Math.max(score, cat.score());
                RectF b = d.boundingBox();
                // prefer confident and large (the TV usually fills a good part of the view)
                float rank = score * (float) Math.sqrt(Math.max(1f, b.width() * b.height()));
                if (best == null || rank > best[5]) best = new float[]{b.left, b.top, b.right, b.bottom, score, rank};
            }
            return best == null ? null : new float[]{best[0], best[1], best[2], best[3], best[4]};
        } catch (Throwable t) {
            error = t.getMessage();
            return null;
        }
    }

    void close() {
        try {
            if (detector != null) detector.close();
        } catch (Throwable ignored) {
        }
        detector = null;
    }
}
