package me.laumss.notipal.clipboard;

import android.os.Parcel;
import android.os.Parcelable;
import android.util.Size;
import dalvik.system.PathClassLoader;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import me.laumss.notipal.common.Log;

public final class ClipboardStickerEngine {
    private static final String TAG = "ClipboardStickerEngine";
    private final Class<?> api, trail, element, geometry, convert, pointUtils;
    private final Object points;
    private final Parcelable.Creator<?> creator;

    public ClipboardStickerEngine(String apk, String nativeLibraryDir) throws Exception {

        ClassLoader loader = new PathClassLoader(apk, nativeLibraryDir, String.class.getClassLoader());
        api = Class.forName("com.example.libsupernote.SuperNoteNote", true, loader);
        trail = Class.forName("com.example.libsupernote.JniTrailData", true, loader);
        element = Class.forName("com.ratta.supernote.plugincommon.data.common.trail.Element", true, loader);
        geometry = Class.forName("com.ratta.supernote.plugincommon.data.common.trail.Geometry", true, loader);
        convert = Class.forName("com.ratta.supernote.pluginclient.utils.ConvertUtils", true, loader);
        pointUtils = Class.forName("com.ratta.supernote.plugincommon.utils.PointUtils", true, loader);
        points = pointUtils.getMethod("getInstance").invoke(null);
        creator = (Parcelable.Creator<?>) element.getField("CREATOR").get(null);

        api.getMethod("generateStickerByTrails", int.class, int.class, List.class, String.class);
        Log.INSTANCE.i(TAG, "[CLIP-LOCAL] loaded apk=" + apk + " libs=" + nativeLibraryDir);
    }

    public Object copyElement(Parcelable source) {
        Parcel parcel = Parcel.obtain();
        try {
            source.writeToParcel(parcel, 0);
            parcel.setDataPosition(0);
            Object copy = creator.createFromParcel(parcel);
            if (parcel.dataAvail() != 0) throw new IllegalStateException("Element parcel version mismatch");
            return copy;
        } finally { parcel.recycle(); }
    }

    public Object convertElement(Object copy, int machine, int rotation, Size pageSize,
            int maxX, int maxY, int layer) throws Exception {
        if (!element.isInstance(copy)) throw new IllegalArgumentException("Foreign Element class");
        setElement(copy, "setMaxX", maxX); setElement(copy, "setMaxY", maxY);
        setElement(copy, "setLayerNum", layer);

        setElement(copy, "setPointCoordType", 1);
        setElement(copy, "setPageNum", (Integer) element.getMethod("getPageNum").invoke(copy) + 1);
        int type = (Integer) element.getMethod("getType").invoke(copy);
        int nativeMaxX = (Integer) pointUtils.getMethod("getRealMaxX", int.class, int.class).invoke(points, machine, rotation);
        int nativeMaxY = (Integer) pointUtils.getMethod("getRealMaxY", int.class, int.class).invoke(points, machine, rotation);
        Size targetSize = (Size) pointUtils.getMethod("getPageSize", int.class, int.class).invoke(null, nativeMaxX, nativeMaxY);
        Object out = trail.getConstructor().newInstance();
        setTrail(out, "set_walcom_emr_type", 26);
        trail.getMethod("set_write_app_name", String.class).invoke(out, "superNoteNote");
        convert.getMethod("baseTrail2Jni", trail, element, Size.class).invoke(null, out, copy, targetSize);
        setTrail(out, "set_max_x", nativeMaxX); setTrail(out, "set_max_y", nativeMaxY);
        trail.getMethod("set_m_flag_draw_use_cont", boolean.class).invoke(out, false);
        if (type == 0 || type == 800) {
            String method = type == 0 ? "stroke2Jni" : "fiveStar2Jni";
            convert.getMethod(method, trail, element, Size.class).invoke(null, out, copy, pageSize);
        } else if (type == 700) {
            Object geo = element.getMethod("getGeometry").invoke(copy);
            geo = convert.getMethod("pageGeometryConvert", geometry, Size.class, Size.class)
                    .invoke(null, geo, targetSize, pageSize);
            Object info = convert.getMethod("geometry2Jni", geometry).invoke(null, geo);
            String layerName = (String) convert.getMethod("getLayerName", int.class).invoke(null, layer);
            Method generate = api.getMethod("generateGeometryTrail", int.class, int.class, int.class,
                    int.class, String.class, int.class, info.getClass());
            out = generate.invoke(null, machine, rotation, 3, element.getMethod("getPageNum").invoke(copy),
                    layerName, element.getMethod("getTrailNumInPage").invoke(copy), info);
            if (out == null) throw new IOException("Geometry conversion failed");
        } else { throw new IllegalArgumentException("Unsupported clipboard element type " + type); }
        setTrail(out, "set_m_draw_version", 3);
        convert.getMethod("saveUserData", trail, String.class, element).invoke(null, out, "", copy);
        float[][] contours = (float[][]) trail.getMethod("get_contours").invoke(out);
        int[] hierarchy = (int[]) trail.getMethod("get_hierarchy").invoke(out);
        if (contours != null && contours.length > 0 && (hierarchy == null || hierarchy.length != contours.length * 4)) {
            hierarchy = ContourHierarchy.rebuild(contours);
            trail.getMethod("set_hierarchy", int[].class).invoke(out, (Object) hierarchy);
        }
        int[] samples = (int[]) trail.getMethod("get_points").invoke(out);
        if (type == 0 && (samples == null || samples.length == 0) &&
                (contours == null || contours.length == 0)) throw new IOException("Empty clipboard stroke");
        Log.INSTANCE.i(TAG, "[CLIP-LOCAL] converted trail=" + element.getMethod("getTrailNumInPage").invoke(copy)
                + " type=" + type + " pointValues=" + (samples == null ? 0 : samples.length)
                + " contours=" + (contours == null ? 0 : contours.length)
                + " hierarchyValues=" + (hierarchy == null ? 0 : hierarchy.length)
                + " page=" + pageSize + " target=" + targetSize);
        return out;
    }

    public void write(List<Object> trails, int machine, int rotation, String path) throws Exception {
        if (trails.isEmpty()) throw new IllegalArgumentException("Empty clipboard selection");
        File destination = new File(path);
        if (destination.exists()) throw new IOException("Clipboard destination already exists");
        File pending = File.createTempFile(".notipal-clip-", ".sticker", destination.getParentFile());
        if (!pending.delete()) throw new IOException("Cannot prepare clipboard output");
        try {
            Object result = api.getMethod("generateStickerByTrails", int.class, int.class, List.class, String.class)
                    .invoke(null, machine, rotation, new ArrayList<>(trails), pending.getAbsolutePath());
            if (!Boolean.TRUE.equals(result) || pending.length() == 0) throw new IOException("Native sticker generation failed");
            if (destination.exists() || !pending.renameTo(destination)) throw new IOException("Cannot publish clipboard output");
            Log.INSTANCE.i(TAG, "[CLIP-LOCAL] saved count=" + trails.size() + " bytes=" + destination.length() + " path=" + path);
        } finally { if (pending.exists()) pending.delete(); }
    }

    private void setElement(Object object, String method, int value) throws Exception {
        element.getMethod(method, int.class).invoke(object, value);
    }
    private void setTrail(Object object, String method, int value) throws Exception {
        trail.getMethod(method, int.class).invoke(object, value);
    }
}
