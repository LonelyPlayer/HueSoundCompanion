package app.huebeat;

import android.media.audiofx.Visualizer;
import android.os.Handler;
import android.os.HandlerThread;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import app.huebeat.core.Analyzer;

/**
 * Fallback source for apps that block audio capture (Spotify, SoundCloud, …): Android's
 * Visualizer on the global output mix. It delivers about 20 coarse snapshots per second
 * (waveform + spectrum) instead of the audio itself, which is enough for beats, bands and tempo.
 */
final class VisualizerSource {
    final Analyzer analyzer = new Analyzer(48000, true);
    private HandlerThread thread;
    private Visualizer viz;
    private volatile float lastRms = 0f, lastScale = 1f;
    private boolean measuring = false;
    private final Visualizer.MeasurementPeakRms meas = new Visualizer.MeasurementPeakRms();
    private float[] mags = new float[0];
    String error = null;

    /** @return true if the visualizer is running */
    boolean start() {
        thread = new HandlerThread("huebeat-viz");
        thread.start();
        CountDownLatch done = new CountDownLatch(1);
        new Handler(thread.getLooper()).post(() -> {
            try {
                viz = new Visualizer(0); // 0 = everything the phone plays
                viz.setEnabled(false);
                int[] range = Visualizer.getCaptureSizeRange();
                viz.setCaptureSize(Math.min(1024, range[1]));
                // Normalized data keeps full 8-bit detail at any volume, but flattens loudness.
                // The real loudness is measured separately and put back (see scale below).
                viz.setScalingMode(Visualizer.SCALING_MODE_NORMALIZED);
                measuring = viz.setMeasurementMode(Visualizer.MEASUREMENT_MODE_PEAK_RMS) == Visualizer.SUCCESS;
                viz.setDataCaptureListener(new Visualizer.OnDataCaptureListener() {
                    @Override
                    public void onWaveFormDataCapture(Visualizer v, byte[] w, int samplingRate) {
                        double sq = 0;
                        for (byte b : w) {
                            int x = (b & 0xFF) - 128;
                            sq += x * x;
                        }
                        float normRms = (float) Math.sqrt(sq / Math.max(1, w.length)) / 128f;
                        float scale = 1f;
                        if (measuring && normRms > 1e-4f) {
                            try {
                                v.getMeasurementPeakRms(meas);
                                // millibels as played -> linear; restores verse/chorus/drop dynamics
                                float realRms = (float) Math.pow(10, meas.mRms / 2000.0);
                                scale = realRms / normRms;
                            } catch (Throwable t) {
                                measuring = false;
                            }
                        }
                        lastScale = scale;
                        lastRms = normRms * scale;
                    }

                    @Override
                    public void onFftDataCapture(Visualizer v, byte[] fft, int samplingRate) {
                        int n = fft.length / 2;
                        if (mags.length != n) mags = new float[n];
                        mags[0] = Math.abs(fft[0]) / 128f;
                        for (int k = 1; k < n; k++) {
                            float re = fft[2 * k], im = fft[2 * k + 1];
                            mags[k] = (float) Math.sqrt(re * re + im * im) / 128f * lastScale;
                        }
                        mags[0] *= lastScale;
                        float binHz = (samplingRate / 1000f) / fft.length;
                        analyzer.pushSpectrum(mags, n, binHz, lastRms, System.nanoTime());
                    }
                }, Visualizer.getMaxCaptureRate(), true, true);
                if (viz.setEnabled(true) != Visualizer.SUCCESS) throw new IllegalStateException("could not enable");
            } catch (Throwable t) {
                error = t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
                releaseViz();
            }
            done.countDown();
        });
        try {
            done.await(3, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (viz == null) {
            stop();
            if (error == null) error = "not available";
            return false;
        }
        return true;
    }

    private void releaseViz() {
        if (viz != null) {
            try {
                viz.setEnabled(false);
            } catch (Throwable ignored) {
            }
            try {
                viz.release();
            } catch (Throwable ignored) {
            }
            viz = null;
        }
    }

    void stop() {
        HandlerThread t = thread;
        thread = null;
        if (t != null) {
            new Handler(t.getLooper()).post(this::releaseViz);
            t.quitSafely();
        }
    }
}
