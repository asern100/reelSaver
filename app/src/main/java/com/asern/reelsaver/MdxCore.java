package com.asern.reelsaver;

import java.nio.FloatBuffer;

/**
 * Signal processing for the MDX-Net vocal model (UVR "Voc_FT"): turns stereo 44.1 kHz audio
 * into the model's spectrogram chunks and back. Pure Java (no Android) so it can be tested on a PC.
 *
 * Model input/output: float[1][4][3072][256] = [L.re, L.im, R.re, R.im] x freq bins x frames.
 * Audio buffers are interleaved stereo floats (L, R, L, R…).
 * Audio is cut into overlapping windows of CHUNK samples; the middle GEN samples of each are kept.
 */
public final class MdxCore {
    public static final int SAMPLE_RATE = 44100;
    public static final int N_FFT = 7680, HOP = 1024, DIM_F = 3072, DIM_T = 256;
    public static final int NB = N_FFT / 2 + 1;
    public static final int CHUNK = HOP * (DIM_T - 1);   // samples per model run (~5.9 s)
    public static final int TRIM = N_FFT / 2;
    public static final int GEN = CHUNK - 2 * TRIM;      // new samples produced per run (~5.7 s)
    public static final int SPEC_SIZE = 4 * DIM_F * DIM_T;

    private final double[] win = new double[N_FFT];
    private final double[] wsumInv;                      // 1 / sum of squared windows (overlap-add)
    private final Fft fft = new Fft(N_FFT);
    private final double[] zr = new double[N_FFT], zi = new double[N_FFT];
    private final double[] yr = new double[N_FFT], yi = new double[N_FFT];

    public MdxCore() {
        for (int m = 0; m < N_FFT; m++) win[m] = 0.5 - 0.5 * Math.cos(2 * Math.PI * m / N_FFT);
        double[] ws = new double[CHUNK + N_FFT];
        for (int t = 0; t < DIM_T; t++)
            for (int m = 0; m < N_FFT; m++) ws[t * HOP + m] += win[m] * win[m];
        wsumInv = new double[ws.length];
        for (int i = 0; i < ws.length; i++) wsumInv[i] = 1.0 / Math.max(ws[i], 1e-8);
    }

    /** Number of model runs needed for [n] samples per channel */
    public static int chunkCount(int n) {
        int pad = GEN - n % GEN;
        return (n + pad) / GEN;
    }

    /** Sample of the zero-padded signal seen by chunk [k] at position [t] (0..CHUNK-1) */
    private static float sample(FloatBuffer mix, int n, int k, int t, int ch) {
        int i = k * GEN + t - TRIM;
        return (i >= 0 && i < n) ? mix.get(2 * i + ch) : 0f;
    }

    /**
     * Spectrogram of chunk [k] of interleaved stereo [mix] ([n] samples per channel) into [spec].
     * STFT: periodic Hann window, centered frames with reflect padding (like torch.stft center=True).
     */
    public void stft(FloatBuffer mix, int n, int k, float[] spec) {
        final int half = N_FFT / 2;
        for (int t = 0; t < DIM_T; t++) {
            int start = t * HOP - half;
            for (int m = 0; m < N_FFT; m++) {
                int u = start + m;
                if (u < 0) u = -u; else if (u >= CHUNK) u = 2 * (CHUNK - 1) - u;
                zr[m] = sample(mix, n, k, u, 0) * win[m];   // left in the real part
                zi[m] = sample(mix, n, k, u, 1) * win[m];   // right in the imaginary part
            }
            fft.transform(zr, zi, yr, yi, false);
            for (int f = 0; f < DIM_F; f++) {
                int nf = (N_FFT - f) % N_FFT;
                // Split the packed transform back into the left and right spectra
                double lr = 0.5 * (yr[f] + yr[nf]), li = 0.5 * (yi[f] - yi[nf]);
                double rr = 0.5 * (yi[f] + yi[nf]), ri = -0.5 * (yr[f] - yr[nf]);
                if (f < 3) { lr = li = rr = ri = 0; }        // lowest bins are muted, as in UVR
                spec[(0 * DIM_F + f) * DIM_T + t] = (float) lr;
                spec[(1 * DIM_F + f) * DIM_T + t] = (float) li;
                spec[(2 * DIM_F + f) * DIM_T + t] = (float) rr;
                spec[(3 * DIM_F + f) * DIM_T + t] = (float) ri;
            }
        }
    }

    /**
     * Inverse of [stft] for the model output of chunk [k]: writes the chunk's GEN new samples
     * (multiplied by [gain]) into interleaved stereo [out] ([n] samples per channel).
     */
    public void istft(float[] spec, int n, int k, float gain, FloatBuffer out) {
        double[] acc = new double[2 * (CHUNK + N_FFT)];
        for (int t = 0; t < DIM_T; t++) {
            java.util.Arrays.fill(zr, 0); java.util.Arrays.fill(zi, 0);
            for (int f = 0; f < DIM_F; f++) {
                double lr = spec[(0 * DIM_F + f) * DIM_T + t], li = spec[(1 * DIM_F + f) * DIM_T + t];
                double rr = spec[(2 * DIM_F + f) * DIM_T + t], ri = spec[(3 * DIM_F + f) * DIM_T + t];
                if (f == 0) { li = 0; ri = 0; }              // DC must be real
                // Z = L + iR, plus the mirrored (conjugate) half so both outputs are real
                zr[f] = lr - ri; zi[f] = li + rr;
                if (f > 0) { zr[N_FFT - f] = lr + ri; zi[N_FFT - f] = -li + rr; }
            }
            fft.transform(zr, zi, yr, yi, true);
            int base = t * HOP;
            for (int m = 0; m < N_FFT; m++) {
                acc[2 * (base + m)] += yr[m] / N_FFT * win[m];
                acc[2 * (base + m) + 1] += yi[m] / N_FFT * win[m];
            }
        }
        int first = N_FFT / 2 + TRIM;
        for (int s = 0; s < GEN; s++) {
            int i = k * GEN + s;
            if (i >= n) break;
            int a = first + s;
            out.put(2 * i, (float) (acc[2 * a] * wsumInv[a] * gain));
            out.put(2 * i + 1, (float) (acc[2 * a + 1] * wsumInv[a] * gain));
        }
    }

    /** Mixed-radix complex FFT (any size; fast for sizes made of 2, 3, 4, 5), kissfft style */
    static final class Fft {
        private final int n;
        private final int[] factors;          // pairs (radix p, remaining length m)
        private final double[] twr, twi;      // exp(-2*pi*i*k/n)
        private final double[] sr = new double[16], si = new double[16];
        private double[] inR, inI, outR, outI;
        private boolean inv;

        Fft(int n) {
            this.n = n;
            twr = new double[n]; twi = new double[n];
            for (int k = 0; k < n; k++) {
                double a = -2 * Math.PI * k / n;
                twr[k] = Math.cos(a); twi[k] = Math.sin(a);
            }
            int[] tmp = new int[64]; int c = 0, rest = n, p = 4;
            do {
                while (rest % p != 0) {
                    if (p == 4) p = 2; else if (p == 2) p = 3; else p += 2;
                    if (p > Math.sqrt(rest)) p = rest;
                }
                rest /= p;
                tmp[c++] = p; tmp[c++] = rest;
            } while (rest > 1);
            factors = java.util.Arrays.copyOf(tmp, c);
        }

        /** out = DFT(in); [inverse] uses exp(+...) and is NOT scaled by 1/n */
        void transform(double[] ir, double[] ii, double[] or, double[] oi, boolean inverse) {
            inR = ir; inI = ii; outR = or; outI = oi; inv = inverse;
            work(0, 0, 1, 0);
        }

        private void work(int out, int in, int fstride, int fi) {
            int p = factors[fi], m = factors[fi + 1];
            int end = out + p * m;
            if (m == 1) {
                for (int o = out; o < end; o++, in += fstride) { outR[o] = inR[in]; outI[o] = inI[in]; }
            } else {
                for (int o = out; o < end; o += m, in += fstride) work(o, in, fstride * p, fi + 2);
            }
            butterfly(out, fstride, m, p);
        }

        private void butterfly(int out, int fstride, int m, int p) {
            double[] scr = p <= 16 ? sr : new double[p], sci = p <= 16 ? si : new double[p];
            double sign = inv ? -1 : 1;
            for (int u = 0; u < m; u++) {
                for (int q = 0, k = u; q < p; q++, k += m) { scr[q] = outR[out + k]; sci[q] = outI[out + k]; }
                for (int q1 = 0, k = u; q1 < p; q1++, k += m) {
                    int tw = 0;
                    double accR = scr[0], accI = sci[0];
                    for (int q = 1; q < p; q++) {
                        tw += fstride * k; if (tw >= n) tw -= n;
                        double wr = twr[tw], wi = sign * twi[tw];
                        accR += scr[q] * wr - sci[q] * wi;
                        accI += scr[q] * wi + sci[q] * wr;
                    }
                    outR[out + k] = accR; outI[out + k] = accI;
                }
            }
        }
    }
}
