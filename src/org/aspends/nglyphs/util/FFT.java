package org.aspends.nglyphs.util;

/**
 * Lightweight FFT implementation for real-time audio visualization.
 */
public class FFT {

    /**
     * Computes the FFT of the input signal.
     * @param x Real part of the input signal.
     * @param y Imaginary part of the input signal (will be modified).
     */
    public static void fft(double[] x, double[] y) {
        int n = x.length;
        if (n == 0) return;

        // Bit-reversal permutation
        int j = 0;
        for (int i = 0; i < n; i++) {
            if (i < j) {
                double tempX = x[i];
                double tempY = y[i];
                x[i] = x[j];
                y[i] = y[j];
                x[j] = tempX;
                y[j] = tempY;
            }
            int m = n >> 1;
            while (m >= 1 && j >= m) {
                j -= m;
                m >>= 1;
            }
            j += m;
        }

        // Butterfly computation
        for (int len = 2; len <= n; len <<= 1) {
            double angle = -2 * Math.PI / len;
            double wlenX = Math.cos(angle);
            double wlenY = Math.sin(angle);
            for (int i = 0; i < n; i += len) {
                double wX = 1;
                double wY = 0;
                for (int k = 0; k < len / 2; k++) {
                    double uX = x[i + k];
                    double uY = y[i + k];
                    double vX = x[i + k + len / 2] * wX - y[i + k + len / 2] * wY;
                    double vY = x[i + k + len / 2] * wX + y[i + k + len / 2] * wY;
                    x[i + k] = uX + vX;
                    y[i + k] = uY + vY;
                    x[i + k + len / 2] = uX - vX;
                    y[i + k + len / 2] = uY - vY;
                    double tempW = wX * wlenX - wY * wlenY;
                    wY = wX * wlenY + wY * wlenX;
                    wX = tempW;
                }
            }
        }
    }
}
