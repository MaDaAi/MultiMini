// Linux JAWT identity bridge for our own Swing overlay only.
#include <jni.h>
#include <jawt.h>
#include <jawt_md.h>
#include <X11/Xatom.h>
#include <unistd.h>

extern "C" JNIEXPORT jlong JNICALL
Java_app_multimin_backend_LinuxWindowIdentity_windowId(JNIEnv* env, jclass, jobject window) {
    if (!window) return 0;
    JAWT api{};
    api.version = JAWT_VERSION_1_4;
    if (JAWT_GetAWT(env, &api) == JNI_FALSE) return 0;
    JAWT_DrawingSurface* surface = api.GetDrawingSurface(env, window);
    if (!surface) return 0;
    const jint lock = surface->Lock(surface);
    if ((lock & JAWT_LOCK_ERROR) != 0) {
        api.FreeDrawingSurface(surface);
        return 0;
    }
    jlong result = 0;
    JAWT_DrawingSurfaceInfo* info = surface->GetDrawingSurfaceInfo(surface);
    if (info && info->platformInfo) {
        auto* x11 = static_cast<JAWT_X11DrawingSurfaceInfo*>(info->platformInfo);
        if (x11->drawable && x11->display) {
            // The property is attached only to the component supplied by Java.
            // Namespace-local PID matches the backend inside a Flatpak too.
            const unsigned long owner = static_cast<unsigned long>(getpid());
            const Atom pid = XInternAtom(x11->display, "_NET_WM_PID", False);
            XChangeProperty(x11->display, x11->drawable, pid, XA_CARDINAL, 32,
                            PropModeReplace, reinterpret_cast<const unsigned char*>(&owner), 1);
            XFlush(x11->display);
            result = static_cast<jlong>(x11->drawable);
        }
    }
    if (info) surface->FreeDrawingSurfaceInfo(info);
    surface->Unlock(surface);
    api.FreeDrawingSurface(surface);
    return result;
}
