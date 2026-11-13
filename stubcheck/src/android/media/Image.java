package android.media;
public abstract class Image implements java.io.Closeable { public abstract int getWidth(); public abstract int getHeight(); public abstract Plane[] getPlanes(); public abstract void close(); public abstract static class Plane { public abstract java.nio.ByteBuffer getBuffer(); public abstract int getRowStride(); public abstract int getPixelStride(); } }
