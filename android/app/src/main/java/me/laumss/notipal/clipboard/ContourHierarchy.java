package me.laumss.notipal.clipboard;

import java.util.Arrays;

public final class ContourHierarchy {
    private ContourHierarchy() {}

    public static int[] rebuild(float[][] contours) {
        int n = contours.length;
        int[] links = new int[Math.multiplyExact(n, 4)];
        Arrays.fill(links, -1);
        double[] areas = new double[n];
        double[][] bounds = new double[n][4];
        for (int i = 0; i < n; i++) {
            float[] ring = contours[i];
            if (ring == null || ring.length % 2 != 0) throw new IllegalArgumentException("Invalid contour " + i);
            bounds[i] = new double[]{Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY,
                    Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY};
            double area = 0;
            for (int p = 0; p < ring.length; p += 2) {
                double x = ring[p], y = ring[p + 1];
                if (!Double.isFinite(x) || !Double.isFinite(y)) throw new IllegalArgumentException("Non-finite contour " + i);
                bounds[i][0] = Math.min(bounds[i][0], x); bounds[i][1] = Math.min(bounds[i][1], y);
                bounds[i][2] = Math.max(bounds[i][2], x); bounds[i][3] = Math.max(bounds[i][3], y);
                int q = (p + 2) % ring.length;
                area += x * ring[q + 1] - y * ring[q];
            }
            areas[i] = Math.abs(area);
        }
        for (int i = 0; i < n; i++) {
            double parentArea = Double.POSITIVE_INFINITY;
            for (int j = 0; j < n; j++) {
                if (areas[j] <= areas[i] || areas[j] >= parentArea) continue;
                double[] a = bounds[i], b = bounds[j];
                if (a[0] < b[0] || a[1] < b[1] || a[2] > b[2] || a[3] > b[3]) continue;
                if (contains(contours[j], contours[i])) {
                    links[4 * i + 3] = j;
                    parentArea = areas[j];
                }
            }
        }
        int[] lastChild = new int[n + 1];
        Arrays.fill(lastChild, -1);
        for (int i = 0; i < n; i++) {
            int parent = links[4 * i + 3];
            int slot = parent < 0 ? n : parent;
            int previous = lastChild[slot];
            if (previous >= 0) {
                links[4 * previous] = i;
                links[4 * i + 1] = previous;
            } else if (parent >= 0) {
                links[4 * parent + 2] = i;
            }
            lastChild[slot] = i;
        }
        return links;
    }

    private static boolean contains(float[] outer, float[] inner) {
        boolean interior = false;
        for (int i = 0; i < inner.length; i += 2) {
            int location = locate(outer, inner[i], inner[i + 1]);
            if (location < 0) return false;
            interior |= location > 0;
            int next = (i + 2) % inner.length;
            location = locate(outer, ((double) inner[i] + inner[next]) / 2,
                    ((double) inner[i + 1] + inner[next + 1]) / 2);
            if (location < 0) return false;
            interior |= location > 0;
        }
        return interior;
    }

    private static int locate(float[] ring, double x, double y) {
        boolean inside = false;
        for (int i = 0, j = ring.length - 2; i < ring.length; j = i, i += 2) {
            double ax = ring[j], ay = ring[j + 1], bx = ring[i], by = ring[i + 1];
            double cross = (x - ax) * (by - ay) - (y - ay) * (bx - ax);
            if (Math.abs(cross) < 1e-7 && x >= Math.min(ax, bx) && x <= Math.max(ax, bx)
                    && y >= Math.min(ay, by) && y <= Math.max(ay, by)) return 0;
            if ((ay > y) != (by > y) && x < (bx - ax) * (y - ay) / (by - ay) + ax) inside = !inside;
        }
        return inside ? 1 : -1;
    }
}
