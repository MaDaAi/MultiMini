#define NOMINMAX
#include <windows.h>
#include <jni.h>
#include <new>
#include <string>
#include <wincodec.h>
#include <wrl/client.h>
#include <dwmapi.h>

struct Mapping { HANDLE handle; const void* pixels; jint bytes; };

struct Exclusion { DWORD process; int applied; };
static BOOL CALLBACK exclude_window(HWND window, LPARAM parameter) {
    auto state = reinterpret_cast<Exclusion*>(parameter);
    DWORD process = 0; GetWindowThreadProcessId(window, &process);
    if (process == state->process && IsWindowVisible(window) && SetWindowDisplayAffinity(window, 0x11)) state->applied++;
    return TRUE;
}
extern "C" JNIEXPORT jint JNICALL Java_app_multimin_backend_SharedFrameBridge_excludeOwnWindows
  (JNIEnv*, jclass) {
    Exclusion state{GetCurrentProcessId(),0}; EnumWindows(exclude_window, reinterpret_cast<LPARAM>(&state)); return state.applied;
}

extern "C" JNIEXPORT jlong JNICALL Java_app_multimin_backend_SharedFrameBridge_open
  (JNIEnv* env, jclass, jstring name, jint bytes) {
    if (bytes <= 0 || bytes > 256 * 1024 * 1024) return 0;
    const jchar* chars = env->GetStringChars(name, nullptr);
    if (!chars) return 0;
    std::wstring mapping_name(reinterpret_cast<const wchar_t*>(chars), env->GetStringLength(name));
    env->ReleaseStringChars(name, chars);
    HANDLE handle = OpenFileMappingW(FILE_MAP_READ, FALSE, mapping_name.c_str());
    if (!handle) return 0;
    const void* pixels = MapViewOfFile(handle, FILE_MAP_READ, 0, 0, static_cast<SIZE_T>(bytes));
    if (!pixels) { CloseHandle(handle); return 0; }
    auto mapping = new (std::nothrow) Mapping{handle, pixels, bytes};
    if (!mapping) { UnmapViewOfFile(pixels); CloseHandle(handle); return 0; }
    return reinterpret_cast<jlong>(mapping);
}

extern "C" JNIEXPORT void JNICALL Java_app_multimin_backend_SharedFrameBridge_copy
  (JNIEnv* env, jclass, jlong address, jintArray output) {
    auto mapping = reinterpret_cast<Mapping*>(address);
    if (mapping && env->GetArrayLength(output) <= mapping->bytes / 4)
        env->SetIntArrayRegion(output, 0, env->GetArrayLength(output), static_cast<const jint*>(mapping->pixels));
}

extern "C" JNIEXPORT void JNICALL Java_app_multimin_backend_SharedFrameBridge_release
  (JNIEnv*, jclass, jlong address) {
    auto mapping = reinterpret_cast<Mapping*>(address);
    if (mapping) { UnmapViewOfFile(mapping->pixels); CloseHandle(mapping->handle); delete mapping; }
}

extern "C" JNIEXPORT jboolean JNICALL Java_app_multimin_backend_SharedFrameBridge_resize
  (JNIEnv* env, jclass, jintArray input, jint width, jint height, jintArray output, jint target_width, jint target_height) {
    if(width<=0 || height<=0 || target_width<=0 || target_height<=0 ||
       static_cast<long long>(width)*height>64*1024*1024 ||
       static_cast<long long>(target_width)*target_height>64*1024*1024 ||
       env->GetArrayLength(input)!=static_cast<long long>(width)*height ||
       env->GetArrayLength(output)!=static_cast<long long>(target_width)*target_height) return JNI_FALSE;
    jint* source=env->GetIntArrayElements(input,nullptr);
    if(!source)return JNI_FALSE;
    jint* destination=env->GetIntArrayElements(output,nullptr);
    if(!destination){env->ReleaseIntArrayElements(input,source,JNI_ABORT);return JNI_FALSE;}
    HRESULT initialized=CoInitializeEx(nullptr,COINIT_MULTITHREADED);
    HRESULT result=E_FAIL;
    {
        Microsoft::WRL::ComPtr<IWICImagingFactory> factory;
        Microsoft::WRL::ComPtr<IWICBitmap> bitmap;
        Microsoft::WRL::ComPtr<IWICBitmapScaler> scaler;
        result=CoCreateInstance(CLSID_WICImagingFactory,nullptr,CLSCTX_INPROC_SERVER,IID_PPV_ARGS(&factory));
        if(SUCCEEDED(result)) result=factory->CreateBitmapFromMemory(width,height,GUID_WICPixelFormat32bppBGRA,
            width*4,width*height*4,reinterpret_cast<BYTE*>(source),&bitmap);
        if(SUCCEEDED(result)) result=factory->CreateBitmapScaler(&scaler);
        if(SUCCEEDED(result)) result=scaler->Initialize(bitmap.Get(),target_width,target_height,WICBitmapInterpolationModeHighQualityCubic);
        if(SUCCEEDED(result)) result=scaler->CopyPixels(nullptr,target_width*4,target_width*target_height*4,reinterpret_cast<BYTE*>(destination));
    }
    if(SUCCEEDED(initialized)) CoUninitialize();
    env->ReleaseIntArrayElements(input,source,JNI_ABORT);
    env->ReleaseIntArrayElements(output,destination,SUCCEEDED(result)?0:JNI_ABORT);
    return SUCCEEDED(result)?JNI_TRUE:JNI_FALSE;
}

struct WindowSearch { RECT bounds; HWND result; LONG area; };
static BOOL CALLBACK find_destination(HWND window,LPARAM parameter) {
    auto search=reinterpret_cast<WindowSearch*>(parameter);DWORD process=0;
    GetWindowThreadProcessId(window,&process);RECT rect{};
    if(process!=GetCurrentProcessId() || !IsWindowVisible(window) || !GetWindowRect(window,&rect))return TRUE;
    if(rect.left>search->bounds.left || rect.top>search->bounds.top || rect.right<search->bounds.right || rect.bottom<search->bounds.bottom)return TRUE;
    LONG area=(rect.right-rect.left)*(rect.bottom-rect.top);
    if(!search->result || area<search->area){search->result=window;search->area=area;}
    return TRUE;
}
extern "C" JNIEXPORT jlong JNICALL Java_app_multimin_backend_NativeWindowCompositor_destination
 (JNIEnv*,jclass,jint x,jint y,jint width,jint height) {
    WindowSearch search{{x,y,x+width,y+height},nullptr,0};EnumWindows(find_destination,reinterpret_cast<LPARAM>(&search));
    return reinterpret_cast<jlong>(search.result);
}
extern "C" JNIEXPORT jlong JNICALL Java_app_multimin_backend_NativeWindowCompositor_register
 (JNIEnv*,jclass,jlong destination,jlong source) {
    HWND target=reinterpret_cast<HWND>(destination), origin=reinterpret_cast<HWND>(source);DWORD process=0;
    GetWindowThreadProcessId(target,&process);
    if(process!=GetCurrentProcessId() || !IsWindow(origin) || IsIconic(origin) || target==origin)return 0;
    HTHUMBNAIL thumbnail=nullptr;
    return SUCCEEDED(DwmRegisterThumbnail(target,origin,&thumbnail))?reinterpret_cast<jlong>(thumbnail):0;
}
extern "C" JNIEXPORT jlong JNICALL Java_app_multimin_backend_NativeWindowCompositor_size
 (JNIEnv*,jclass,jlong thumbnail) {
    SIZE size{};if(FAILED(DwmQueryThumbnailSourceSize(reinterpret_cast<HTHUMBNAIL>(thumbnail),&size)))return 0;
    return (static_cast<jlong>(size.cx)<<32)|static_cast<unsigned long>(size.cy);
}
extern "C" JNIEXPORT jboolean JNICALL Java_app_multimin_backend_NativeWindowCompositor_update
 (JNIEnv*,jclass,jlong thumbnail,jlong destination,jint x,jint y,jint width,jint height,
 jint source_x,jint source_y,jint source_width,jint source_height,jint opacity) {
    POINT point{x,y};if(!ScreenToClient(reinterpret_cast<HWND>(destination),&point))return JNI_FALSE;
    DWM_THUMBNAIL_PROPERTIES props{};props.dwFlags=DWM_TNP_RECTDESTINATION|DWM_TNP_RECTSOURCE|DWM_TNP_OPACITY|DWM_TNP_VISIBLE|DWM_TNP_SOURCECLIENTAREAONLY;
    props.rcDestination={point.x,point.y,point.x+width,point.y+height};props.rcSource={source_x,source_y,source_x+source_width,source_y+source_height};
    props.opacity=static_cast<BYTE>(opacity);props.fVisible=TRUE;props.fSourceClientAreaOnly=FALSE;
    return SUCCEEDED(DwmUpdateThumbnailProperties(reinterpret_cast<HTHUMBNAIL>(thumbnail),&props))?JNI_TRUE:JNI_FALSE;
}
extern "C" JNIEXPORT void JNICALL Java_app_multimin_backend_NativeWindowCompositor_unregister
 (JNIEnv*,jclass,jlong thumbnail) {if(thumbnail)DwmUnregisterThumbnail(reinterpret_cast<HTHUMBNAIL>(thumbnail));}
