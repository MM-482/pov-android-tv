package org.videolan.libvlc;

/** Narrow access to the capability field in the pinned LibVLC 3.7.7 wrapper. */
public final class RendererVideoSupport {
    private RendererVideoSupport() {}
    public static boolean isVideoChromecast(RendererItem item) {
        return item != null && "chromecast".equals(item.type)
                && (item.flags & RendererItem.LIBVLC_RENDERER_CAN_VIDEO) != 0;
    }
}
