package android.media.projection;
public class MediaProjection { public static abstract class Callback { public void onStop() {} } public void registerCallback(Callback c,android.os.Handler h) {} public android.hardware.display.VirtualDisplay createVirtualDisplay(String n,int w,int h,int d,int f,android.view.Surface s,Object cb,android.os.Handler hd) { throw null; } public void stop() {} }
