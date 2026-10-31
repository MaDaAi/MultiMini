#include "GameCapture.h"
#include <windows.h>
#include <chrono>
#include <cmath>
#include <cwchar>
#include <cstdlib>
#include <memory>
#include <string>

namespace {
LRESULT CALLBACK window_proc(HWND window, UINT message, WPARAM wparam, LPARAM lparam) {
    if (message == WM_DESTROY) { PostQuitMessage(0); return 0; }
    return DefWindowProcW(window, message, wparam, lparam);
}

std::string utf8(const wchar_t* value) {
    const int length = WideCharToMultiByte(CP_UTF8, 0, value, -1, nullptr, 0, nullptr, nullptr);
    if (length <= 1) return {};
    std::string result(static_cast<size_t>(length), '\0');
    WideCharToMultiByte(CP_UTF8, 0, value, -1, result.data(), length, nullptr, nullptr);
    result.pop_back();
    return result;
}
}

int WINAPI wWinMain(HINSTANCE instance, HINSTANCE, PWSTR command_line, int show) {
    int count = 0;
    LPWSTR* args = CommandLineToArgvW(GetCommandLineW(), &count);
    if (args && count == 2 && std::wcscmp(args[1], L"--probe") == 0) {
        Microsoft::WRL::ComPtr<ID3D11Device> probe_device;
        Microsoft::WRL::ComPtr<ID3D11DeviceContext> probe_context;
        D3D_FEATURE_LEVEL probe_level{};
        const HRESULT result = D3D11CreateDevice(nullptr, D3D_DRIVER_TYPE_HARDWARE, nullptr,
            D3D11_CREATE_DEVICE_BGRA_SUPPORT, nullptr, 0, D3D11_SDK_VERSION,
            &probe_device, &probe_level, &probe_context);
        LocalFree(args);
        return SUCCEEDED(result) && probe_level >= D3D_FEATURE_LEVEL_11_0 ? 0 : 1;
    }
    if (!args || count != 4) { if (args) LocalFree(args); return 2; }
    const int parsed_port = _wtoi(args[1]);
    if (parsed_port <= 0 || parsed_port > 65535) { LocalFree(args); return 2; }
    const std::string source = utf8(args[2]), token = utf8(args[3]);
    LocalFree(args);

    WNDCLASSW cls{};
    cls.lpfnWndProc = window_proc;
    cls.hInstance = instance;
    cls.lpszClassName = L"MultiMinControlledDx11Test";
    if (!RegisterClassW(&cls)) return 3;
    HWND window = CreateWindowExW(0, cls.lpszClassName, L"MultiMin DirectX 11 capture test",
        WS_OVERLAPPEDWINDOW, CW_USEDEFAULT, CW_USEDEFAULT, 976, 579,
        nullptr, nullptr, instance, nullptr);
    if (!window) return 4;

    DXGI_SWAP_CHAIN_DESC swap_desc{};
    swap_desc.BufferDesc.Width = 960;
    swap_desc.BufferDesc.Height = 540;
    swap_desc.BufferDesc.Format = DXGI_FORMAT_B8G8R8A8_UNORM;
    swap_desc.SampleDesc.Count = 1;
    swap_desc.BufferUsage = DXGI_USAGE_RENDER_TARGET_OUTPUT;
    swap_desc.BufferCount = 2;
    swap_desc.OutputWindow = window;
    swap_desc.Windowed = TRUE;
    swap_desc.SwapEffect = DXGI_SWAP_EFFECT_DISCARD;
    Microsoft::WRL::ComPtr<ID3D11Device> device;
    Microsoft::WRL::ComPtr<ID3D11DeviceContext> context;
    Microsoft::WRL::ComPtr<IDXGISwapChain> swap_chain;
    D3D_FEATURE_LEVEL level{};
    const HRESULT created = D3D11CreateDeviceAndSwapChain(nullptr, D3D_DRIVER_TYPE_HARDWARE, nullptr,
        D3D11_CREATE_DEVICE_BGRA_SUPPORT, nullptr, 0, D3D11_SDK_VERSION, &swap_desc,
        &swap_chain, &device, &level, &context);
    if (FAILED(created) || level < D3D_FEATURE_LEVEL_11_0) { DestroyWindow(window); return 5; }
    Microsoft::WRL::ComPtr<ID3D11Texture2D> back_buffer;
    Microsoft::WRL::ComPtr<ID3D11RenderTargetView> target;
    if (FAILED(swap_chain->GetBuffer(0, IID_PPV_ARGS(&back_buffer))) ||
        FAILED(device->CreateRenderTargetView(back_buffer.Get(), nullptr, &target))) {
        DestroyWindow(window); return 6;
    }
    ShowWindow(window, show);
    GameCapture capture(static_cast<unsigned short>(parsed_port), source, token);
    if (!capture.connected()) { DestroyWindow(window); return 7; }
    const auto start = std::chrono::steady_clock::now();
    MSG message{};
    while (true) {
        while (PeekMessageW(&message, nullptr, 0, 0, PM_REMOVE)) {
            if (message.message == WM_QUIT) return 0;
            TranslateMessage(&message);
            DispatchMessageW(&message);
        }
        const float seconds = std::chrono::duration<float>(std::chrono::steady_clock::now() - start).count();
        const float color[4] = {0.45f + 0.45f * std::sin(seconds * 1.8f),
                                0.45f + 0.45f * std::sin(seconds * 1.8f + 2.1f),
                                0.45f + 0.45f * std::sin(seconds * 1.8f + 4.2f), 1.0f};
        context->ClearRenderTargetView(target.Get(), color);
        if (!capture.capture(swap_chain.Get())) return 8;
        swap_chain->Present(1, 0);
    }
}
