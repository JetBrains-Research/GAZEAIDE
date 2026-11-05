package io.github.gazehighlighter;

import org.jetbrains.annotations.Nullable;

import java.awt.Point;
import java.util.List;

/**
 * Affine correction from raw webcam-tracker screen coordinates to actual screen coordinates.
 *
 * <p>UnitEye's generic pre-trained model is coarse and tends to be systematically off (biased
 * toward the webcam, off-center, scaled wrong for the monitor) rather than purely noisy. A
 * per-user affine fit — collected via {@link GazeCalibrationDialog}'s on-screen target points —
 * corrects that bias far more cheaply than retraining the model:
 *
 * <pre>
 *   corrected.x = a * raw.x + b * raw.y + c
 *   corrected.y = d * raw.x + e * raw.y + f
 * </pre>
 *
 * Both rows are independent ordinary-least-squares fits of the same design matrix
 * {@code [raw.x, raw.y, 1]} against the target's x and y respectively, so {@link #fit} builds
 * the 3x3 normal-equations matrix once and solves it twice.
 */
public final class GazeCalibration {

    public static final GazeCalibration IDENTITY = new GazeCalibration(1, 0, 0, 0, 1, 0);

    public final double a, b, c, d, e, f;

    public GazeCalibration(double a, double b, double c, double d, double e, double f) {
        this.a = a; this.b = b; this.c = c;
        this.d = d; this.e = e; this.f = f;
    }

    public Point apply(int rawX, int rawY) {
        int x = (int) Math.round(x(rawX, rawY));
        int y = (int) Math.round(y(rawX, rawY));
        return new Point(x, y);
    }

    public double x(double rawX, double rawY) { return a * rawX + b * rawY + c; }
    public double y(double rawX, double rawY) { return d * rawX + e * rawY + f; }

    /** One (raw tracker point, on-screen target point) correspondence collected during calibration. */
    public static final class Sample {
        public final double rawX, rawY, targetX, targetY;
        public Sample(double rawX, double rawY, double targetX, double targetY) {
            this.rawX = rawX; this.rawY = rawY; this.targetX = targetX; this.targetY = targetY;
        }
    }

    /**
     * Least-squares affine fit from {@code samples}. Needs at least 3 non-degenerate points
     * (in practice a 9-point grid); returns {@code null} if the system is singular (e.g. all
     * points collinear or too few valid samples came in).
     */
    public static @Nullable GazeCalibration fit(List<Sample> samples) {
        if (samples.size() < 3) return null;

        for (Sample sample : samples) {
            if (!Double.isFinite(sample.rawX) || !Double.isFinite(sample.rawY)
                    || !Double.isFinite(sample.targetX) || !Double.isFinite(sample.targetY)) return null;
        }
        // Center and scale before solving: desktop coordinates may be large or negative.
        double mx = samples.stream().mapToDouble(s -> s.rawX).average().orElse(0);
        double my = samples.stream().mapToDouble(s -> s.rawY).average().orElse(0);
        double sx = Math.sqrt(samples.stream().mapToDouble(s -> Math.pow(s.rawX - mx, 2)).average().orElse(0));
        double sy = Math.sqrt(samples.stream().mapToDouble(s -> Math.pow(s.rawY - my, 2)).average().orElse(0));
        if (sx < 1e-6 || sy < 1e-6 || !Double.isFinite(sx + sy)) return null;
        double[][] m = new double[3][3];
        double[] tx = new double[3], ty = new double[3];
        for (Sample s : samples) {
            double[] row = {(s.rawX - mx) / sx, (s.rawY - my) / sy, 1};
            for (int i = 0; i < 3; i++) {
                tx[i] += row[i] * s.targetX; ty[i] += row[i] * s.targetY;
                for (int j = 0; j < 3; j++) m[i][j] += row[i] * row[j];
            }
        }
        double[] rx = solve3x3(m, tx), ry = solve3x3(m, ty);
        if (rx == null || ry == null) return null;
        GazeCalibration fit = new GazeCalibration(rx[0]/sx, rx[1]/sy, rx[2]-rx[0]*mx/sx-rx[1]*my/sy,
                ry[0]/sx, ry[1]/sy, ry[2]-ry[0]*mx/sx-ry[1]*my/sy);
        if (!Double.isFinite(fit.a + fit.b + fit.c + fit.d + fit.e + fit.f)) return null;
        return fit;
    }

    /** Solves {@code m * x = rhs} for a 3x3 system via Gaussian elimination with partial pivoting. */
    private static @Nullable double[] solve3x3(double[][] m, double[] rhs) {
        double[][] a = new double[3][4];
        for (int i = 0; i < 3; i++) {
            System.arraycopy(m[i], 0, a[i], 0, 3);
            a[i][3] = rhs[i];
        }

        for (int col = 0; col < 3; col++) {
            int pivot = col;
            for (int row = col + 1; row < 3; row++)
                if (Math.abs(a[row][col]) > Math.abs(a[pivot][col])) pivot = row;
            if (Math.abs(a[pivot][col]) < 1e-9) return null;   // singular

            double[] tmp = a[col]; a[col] = a[pivot]; a[pivot] = tmp;

            for (int row = 0; row < 3; row++) {
                if (row == col) continue;
                double factor = a[row][col] / a[col][col];
                for (int k = col; k < 4; k++) a[row][k] -= factor * a[col][k];
            }
        }

        return new double[]{ a[0][3] / a[0][0], a[1][3] / a[1][1], a[2][3] / a[2][2] };
    }
}
