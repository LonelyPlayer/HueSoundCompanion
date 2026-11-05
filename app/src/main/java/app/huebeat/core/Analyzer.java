package app.huebeat.core;

import java.util.Arrays;

/**
 * Real-time music analysis: auto-gained frequency bands, beat (kick) and snare/hi-hat onset
 * detection, tempo estimation, sound brightness and a "key color" derived from the harmony.
 *
 * Feed it mono 16-bit PCM with {@link #push}. Every hop (512 samples) it produces a
 * {@link Features} snapshot that is stored with its timestamp, so a renderer can read the
 * analysis "as it was" a few milliseconds ago (used for the Bluetooth latency compensation).
 */
public final class Analyzer {
    private static final int HOP = 512;
    private static final int N = 2048;        // main FFT (bands, onsets)
    private static final int NC = 8192;       // chroma FFT (pitch resolution)
    private static final int CHROMA_EVERY = 8;
    private static final float[] BAND_EDGES = {20, 60, 150, 400, 1500, 4000, 12000};
    private static final int HISTORY = 600;   // ~6 s of snapshots at 48 kHz

    private final int sampleRate;
    private final float[] ring = new float[NC];
    private int ringPos = 0;
    private long totalSamples = 0;
    private int sinceHop = 0;
    private long hopCount = 0;

    private final Fft fft = new Fft(N);
    private final Fft fftC = new Fft(NC);
    private final float[] win = Fft.hann(N);
    private final float[] winC = Fft.hann(NC);
    private final float winSum;
    private final float[] mag = new float[N / 2];
    private final float[] logMag = new float[N / 2];
    private final float[] prevLogMag = new float[N / 2];

    private final int[] bandLo = new int[Features.BANDS], bandHi = new int[Features.BANDS];
    private float binsFor = -1f;
    private int binsCount = 0;
    private long lastNanos = 0, lastLoudNanos = Long.MIN_VALUE / 2;
    private final float[] bandPeak = new float[Features.BANDS];
    private final float[] bandSmooth = new float[Features.BANDS];
    private float levelPeak = 1e-5f, levelSmooth = 0f;

    private final OnsetDetector kick = new OnsetDetector(1.45f, 0.28f);
    private final OnsetDetector snare = new OnsetDetector(1.6f, 0.11f);
    private int kickLo, kickHi, snareLo, snareHi, centLo, centHi;

    private final double[] beatTimes = new double[16];
    private int beatTimesN = 0;
    private double lastBeatT = -10;
    private int predicted = 0;

    private final float[] chroma = new float[12];
    private float keyX = 0f, keyY = 0f;

    private final Features cur = new Features();

    private final Features[] history = new Features[HISTORY];
    private int historyPos = 0, historyCount = 0;

    private final float silenceDb, levelFloor, bandFloor;

    public Analyzer(int sampleRate) {
        this(sampleRate, false);
    }

    /**
     * @param digitalSource true for captured phone audio: it can be very quiet (low media
     *                      volume is applied digitally) but has no background noise, so the
     *                      silence gate and gain floors can go much lower than for a microphone.
     */
    public Analyzer(int sampleRate, boolean digitalSource) {
        this.sampleRate = sampleRate;
        silenceDb = digitalSource ? -85f : -58f;
        levelFloor = digitalSource ? 1e-5f : 1e-3f;
        bandFloor = digitalSource ? 2e-6f : 2e-4f;
        float s = 0;
        for (float w : win) s += w;
        winSum = s;
        for (int b = 0; b < Features.BANDS; b++) bandPeak[b] = 1e-6f;
        setupBins((float) sampleRate / N, N / 2);
    }

    /** Computes which FFT bins belong to the bands for a given resolution. */
    private void setupBins(float binHz, int nBins) {
        if (binHz == binsFor && nBins == binsCount) return;
        binsFor = binHz;
        binsCount = nBins;
        for (int b = 0; b < Features.BANDS; b++) {
            bandLo[b] = Math.min(nBins - 1, Math.max(1, Math.round(BAND_EDGES[b] / binHz)));
            bandHi[b] = Math.max(bandLo[b] + 1, Math.min(nBins, Math.round(BAND_EDGES[b + 1] / binHz)));
        }
        kickLo = Math.max(1, Math.round(35 / binHz));
        kickHi = Math.min(nBins, Math.max(kickLo + 1, Math.round(180 / binHz) + 1));
        snareLo = Math.min(nBins - 1, Math.round(1500 / binHz));
        snareHi = Math.min(nBins, Math.max(snareLo + 1, Math.round(9000 / binHz)));
        centLo = Math.max(1, Math.round(60 / binHz));
        centHi = Math.min(nBins, Math.round(10000 / binHz));
        Arrays.fill(prevLogMag, 0f);
    }

    /** Feeds mono 16-bit samples. {@code endNanos} is the System.nanoTime() when the last sample arrived. */
    public void push(short[] buf, int n, long endNanos) {
        double nsPerSample = 1e9 / sampleRate;
        for (int i = 0; i < n; i++) {
            ring[ringPos] = buf[i] / 32768f;
            ringPos = (ringPos + 1) & (NC - 1);
            totalSamples++;
            if (++sinceHop == HOP) {
                sinceHop = 0;
                process(endNanos - (long) ((n - 1 - i) * nsPerSample));
            }
        }
    }

    /** Feeds mono float samples in -1..1. */
    public void push(float[] buf, int n, long endNanos) {
        double nsPerSample = 1e9 / sampleRate;
        for (int i = 0; i < n; i++) {
            ring[ringPos] = buf[i];
            ringPos = (ringPos + 1) & (NC - 1);
            totalSamples++;
            if (++sinceHop == HOP) {
                sinceHop = 0;
                process(endNanos - (long) ((n - 1 - i) * nsPerSample));
            }
        }
    }

    private void process(long timeNanos) {
        hopCount++;
        // loudness of the last hop
        double sq = 0;
        for (int i = 1; i <= HOP; i++) {
            float v = ring[(ringPos - i) & (NC - 1)];
            sq += v * v;
        }
        float rms = (float) Math.sqrt(sq / HOP);
        // spectrum
        for (int i = 0; i < N; i++) {
            fft.re[i] = ring[(ringPos - N + i) & (NC - 1)] * win[i];
            fft.im[i] = 0f;
        }
        fft.transform();
        float scale = 2f / winSum;
        for (int k = 0; k < N / 2; k++) {
            mag[k] = (float) Math.sqrt(fft.re[k] * fft.re[k] + fft.im[k] * fft.im[k]) * scale;
        }
        boolean chromaNow = hopCount % CHROMA_EVERY == 0 && totalSamples >= NC;
        analyze(N / 2, (float) sampleRate / N, rms, timeNanos, chromaNow ? 1 : 0);
    }

    /**
     * Feeds a ready-made magnitude spectrum instead of PCM audio (used for Android's Visualizer,
     * which only delivers about 20 coarse spectra per second).
     *
     * @param mags  magnitudes for bins 0..nBins-1
     * @param binHz frequency step between bins
     * @param rms   loudness of the same moment (any consistent scale)
     */
    public void pushSpectrum(float[] mags, int nBins, float binHz, float rms, long timeNanos) {
        hopCount++;
        int n = Math.min(nBins, N / 2);
        System.arraycopy(mags, 0, mag, 0, n);
        analyze(n, binHz, rms, timeNanos, 2);
    }

    /** 1 - (1 - a)^k: makes a per-hop smoothing factor independent of the frame rate. */
    private static float rate(float a, float k) {
        return 1f - (float) Math.pow(1f - a, k);
    }

    /** @param chromaMode 0 = none, 1 = from the high-resolution PCM FFT, 2 = from this spectrum */
    private void analyze(int nBins, float binHz, float rms, long timeNanos, int chromaMode) {
        setupBins(binHz, nBins);
        double t = timeNanos / 1e9;
        float k = lastNanos == 0 ? 1f : Math.max(0.2f, Math.min(10f, (timeNanos - lastNanos) / 1e9f / ((float) HOP / sampleRate)));
        lastNanos = timeNanos;

        float db = 20f * (float) Math.log10(rms + 1e-9);
        if (db >= silenceDb) lastLoudNanos = timeNanos;
        boolean silent = timeNanos - lastLoudNanos > 250_000_000L;
        cur.inputMeter = clamp01((db - silenceDb) / -silenceDb);

        // onset detection works on volume-normalised magnitudes, so it behaves the same at any volume
        float onsetGain = 30f / Math.max(levelPeak, levelFloor);
        for (int i = 0; i < nBins; i++) logMag[i] = (float) Math.log1p(onsetGain * mag[i]);

        // bands with automatic gain
        float peakDecay = (float) Math.pow(0.997f, k);
        for (int b = 0; b < Features.BANDS; b++) {
            double p = 0;
            for (int i = bandLo[b]; i < bandHi[b]; i++) p += mag[i] * mag[i];
            float e = (float) Math.sqrt(p / (bandHi[b] - bandLo[b]));
            bandPeak[b] = Math.max(e, Math.max(bandPeak[b] * peakDecay, bandFloor));
            float norm = (float) Math.pow(e / bandPeak[b], 1.4);
            if (silent) norm = 0f;
            float a = norm > bandSmooth[b] ? rate(0.65f, k) : rate(0.18f, k);
            bandSmooth[b] += (norm - bandSmooth[b]) * a;
            cur.bands[b] = bandSmooth[b];
        }
        levelPeak = Math.max(rms, Math.max(levelPeak * peakDecay, levelFloor));
        float lvl = silent ? 0f : (float) Math.pow(rms / levelPeak, 1.2);
        levelSmooth += (lvl - levelSmooth) * (lvl > levelSmooth ? rate(0.5f, k) : rate(0.12f, k));
        cur.level = levelSmooth;
        cur.silent = silent;

        // onsets
        float kickFlux = 0f, snareFlux = 0f;
        for (int i = kickLo; i < kickHi; i++) kickFlux += Math.max(0f, logMag[i] - prevLogMag[i]);
        for (int i = snareLo; i < snareHi; i++) snareFlux += Math.max(0f, logMag[i] - prevLogMag[i]);
        snareFlux /= Math.max(1, (snareHi - snareLo)) / 8f; // comparable scale to the kick bins
        System.arraycopy(logMag, 0, prevLogMag, 0, nBins);

        if (!silent && kick.feed(kickFlux, t)) {
            cur.beatCount++;
            cur.beatStrength = kick.lastStrength;
            beatTimes[beatTimesN % beatTimes.length] = t;
            beatTimesN++;
            lastBeatT = t;
            predicted = 0;
        }
        if (!silent && snare.feed(snareFlux, t)) cur.snareCount++;
        cur.bpm = estimateBpm(t);

        // Coarse spectra (Visualizer) see only part of the audio and miss some kicks. With a stable
        // tempo, fill in up to two missed beats where they should have been.
        if (chromaMode == 2 && !silent && cur.bpm > 0 && predicted < 2 && cur.level > 0.25f) {
            double period = 60.0 / cur.bpm;
            if (t - lastBeatT > period * 1.12) {
                cur.beatCount++;
                cur.beatStrength = 0.5f;
                lastBeatT += period;
                predicted++;
            }
        }

        // brightness of the sound
        double num = 0, den = 0;
        for (int i = centLo; i < centHi; i++) {
            num += i * binHz * mag[i];
            den += mag[i];
        }
        if (den > 1e-9) {
            float c = (float) (num / den);
            float cn = clamp01((float) (Math.log(c / 60.0) / Math.log(10000.0 / 60.0)));
            cur.centroid += (cn - cur.centroid) * rate(0.08f, k);
        }

        // harmony color
        if (!silent) {
            if (chromaMode == 1) updateChroma();
            else if (chromaMode == 2) chromaFromSpectrum(nBins, binHz, rate(0.03f, k));
        }
        float hue = (float) (Math.atan2(keyY, keyX) / (2 * Math.PI));
        if (hue < 0) hue += 1f;
        cur.keyHue = hue;
        cur.tonality = clamp01((float) Math.sqrt(keyX * keyX + keyY * keyY) * 2.5f);

        cur.timeNanos = timeNanos;
        store(cur.copy());
    }

    /** Coarse chroma for low-resolution spectra: only bins high enough to separate semitones. */
    private void chromaFromSpectrum(int nBins, float binHz, float smoothing) {
        Arrays.fill(chroma, 0f);
        // a semitone is ~6% of the frequency, so bins resolve it from about 17.5 bins upward
        int lo = Math.max(1, Math.round(Math.max(400f, binHz * 17.5f) / binHz));
        int hi = Math.min(nBins, Math.round(5000f / binHz));
        float total = 0f;
        for (int i = lo; i < hi; i++) {
            float f = i * binHz;
            int midi = Math.round(69f + 12f * (float) (Math.log(f / 440.0) / Math.log(2)));
            int pc = ((midi % 12) + 12) % 12;
            chroma[pc] += mag[i];
            total += mag[i];
        }
        if (total > 1e-9f) applyChroma(smoothing);
    }

    private void updateChroma() {
        for (int i = 0; i < NC; i++) {
            fftC.re[i] = ring[(ringPos - NC + i) & (NC - 1)] * winC[i];
            fftC.im[i] = 0f;
        }
        fftC.transform();
        Arrays.fill(chroma, 0f);
        float binHz = (float) sampleRate / NC;
        int lo = Math.round(98f / binHz), hi = Math.min(NC / 2, Math.round(4000f / binHz));
        float total = 0f;
        for (int k = lo; k < hi; k++) {
            float p = fftC.re[k] * fftC.re[k] + fftC.im[k] * fftC.im[k];
            float f = k * binHz;
            int midi = Math.round(69f + 12f * (float) (Math.log(f / 440.0) / Math.log(2)));
            int pc = ((midi % 12) + 12) % 12; // 0 = C
            float w = (float) Math.sqrt(p);
            chroma[pc] += w;
            total += w;
        }
        if (total <= 1e-9f) return;
        applyChroma(0.12f);
    }

    private void applyChroma(float smoothing) {
        // Emphasise the strongest pitch classes, then place them on the circle of fifths so that
        // related keys get neighbouring colors.
        float max = 0f;
        for (float c : chroma) max = Math.max(max, c);
        double x = 0, y = 0, sum = 0;
        for (int pc = 0; pc < 12; pc++) {
            double w = Math.pow(chroma[pc] / max, 3);
            double ang = 2 * Math.PI * ((pc * 7) % 12) / 12.0;
            x += w * Math.cos(ang);
            y += w * Math.sin(ang);
            sum += w;
        }
        x /= sum;
        y /= sum;
        keyX += (float) (x - keyX) * smoothing;
        keyY += (float) (y - keyY) * smoothing;
    }

    private float estimateBpm(double now) {
        int n = Math.min(beatTimesN, beatTimes.length);
        if (n < 6) return 0f;
        double last = beatTimes[(beatTimesN - 1) % beatTimes.length];
        if (now - last > 3.0) return 0f;
        double[] iv = new double[n - 1];
        int m = 0;
        for (int i = beatTimesN - n + 1; i < beatTimesN; i++) {
            double d = beatTimes[i % beatTimes.length] - beatTimes[(i - 1) % beatTimes.length];
            if (d > 0.25 && d < 2.0) iv[m++] = d;
        }
        if (m < 4) return 0f;
        Arrays.sort(iv, 0, m);
        double med = iv[m / 2];
        double bpm = 60.0 / med;
        while (bpm < 85) bpm *= 2;
        while (bpm > 175) bpm /= 2;
        return (float) bpm;
    }

    private synchronized void store(Features f) {
        history[historyPos] = f;
        historyPos = (historyPos + 1) % HISTORY;
        if (historyCount < HISTORY) historyCount++;
    }

    /** Latest snapshot whose time is at or before {@code targetNanos} (or the oldest one). */
    public synchronized Features at(long targetNanos) {
        if (historyCount == 0) return null;
        Features best = null;
        for (int i = 1; i <= historyCount; i++) {
            Features f = history[(historyPos - i + HISTORY) % HISTORY];
            best = f;
            if (f.timeNanos <= targetNanos) return f;
        }
        return best;
    }

    public synchronized Features latest() {
        if (historyCount == 0) return null;
        return history[(historyPos - 1 + HISTORY) % HISTORY];
    }

    static float clamp01(float v) {
        return v < 0f ? 0f : (v > 1f ? 1f : v);
    }

    /** Spectral-flux onset detector with an adaptive threshold and a refractory period. */
    private static final class OnsetDetector {
        private final float sensitivity;
        private final float minInterval;
        private final float[] hist = new float[48];
        private int pos = 0, count = 0;
        private double lastOnset = -10;
        private float prev = 0f;
        float lastStrength;

        OnsetDetector(float sensitivity, float minInterval) {
            this.sensitivity = sensitivity;
            this.minInterval = minInterval;
        }

        boolean feed(float flux, double t) {
            boolean onset = false;
            if (count >= 16) {
                float mean = 0f;
                for (int i = 0; i < count; i++) mean += hist[i];
                mean /= count;
                float var = 0f;
                for (int i = 0; i < count; i++) var += (hist[i] - mean) * (hist[i] - mean);
                float std = (float) Math.sqrt(var / count);
                float thr = mean * sensitivity + std * 1.1f + 0.05f;
                if (flux > thr && flux >= prev && t - lastOnset >= minInterval) {
                    onset = true;
                    lastOnset = t;
                    lastStrength = clamp01((flux - thr) / (thr + 1e-6f) * 0.7f + 0.3f);
                }
            }
            hist[pos] = flux;
            pos = (pos + 1) % hist.length;
            if (count < hist.length) count++;
            prev = flux;
            return onset;
        }
    }
}
