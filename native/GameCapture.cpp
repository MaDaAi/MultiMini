#include "GameCapture.h"
#include <algorithm>
#include <cstring>
#include <limits>
#include <utility>

namespace {
#pragma pack(push, 1)
struct FrameHeader {
    char magic[4];
    std::uint16_t source_size;
    std::uint16_t token_size;
    std::uint32_t width;
    std::uint32_t height;
    std::uint32_t length;
};
#pragma pack(pop)
static_assert(sizeof(FrameHeader) == 20);
}

GameCapture::GameCapture(unsigned short port, std::string source, std::string token)
    : source_(std::move(source)), token_(std::move(token)) {
    if (source_.empty() || source_.size() > 4096 || token_.size() != 32) return;
    WSADATA data{};
    if (WSAStartup(MAKEWORD(2, 2), &data) != 0) return;
    socket_ = ::socket(AF_INET, SOCK_STREAM, IPPROTO_TCP);
    if (socket_ == INVALID_SOCKET) { WSACleanup(); return; }
    const BOOL no_delay = TRUE;
    setsockopt(socket_, IPPROTO_TCP, TCP_NODELAY, reinterpret_cast<const char*>(&no_delay), sizeof(no_delay));
    sockaddr_in address{};
    address.sin_family = AF_INET;
    address.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
    address.sin_port = htons(port);
    if (::connect(socket_, reinterpret_cast<const sockaddr*>(&address), sizeof(address)) != 0) {
        closesocket(socket_);
        socket_ = INVALID_SOCKET;
        WSACleanup();
    }
}

GameCapture::~GameCapture() {
    if (socket_ != INVALID_SOCKET) {
        closesocket(socket_);
        WSACleanup();
    }
}

bool GameCapture::send_all(const void* bytes, size_t count) {
    const auto* cursor = static_cast<const char*>(bytes);
    while (count) {
        const int batch = static_cast<int>(std::min(count, static_cast<size_t>(std::numeric_limits<int>::max())));
        const int written = ::send(socket_, cursor, batch, 0);
        if (written <= 0) return false;
        cursor += written;
        count -= written;
    }
    return true;
}

bool GameCapture::update_staging(IDXGISwapChain* swap_chain, ID3D11Texture2D* back_buffer) {
    D3D11_TEXTURE2D_DESC desc{};
    back_buffer->GetDesc(&desc);
    if (desc.Format != DXGI_FORMAT_B8G8R8A8_UNORM || !desc.Width || !desc.Height ||
        desc.Width > 8192 || desc.Height > 8192 || desc.Width * static_cast<std::uint64_t>(desc.Height) * 4 > 64ULL * 1024 * 1024)
        return false;
    if (staging_ && width_ == desc.Width && height_ == desc.Height) return true;
    Microsoft::WRL::ComPtr<ID3D11Device> device;
    if (FAILED(swap_chain->GetDevice(IID_PPV_ARGS(&device)))) return false;
    desc.BindFlags = 0;
    desc.CPUAccessFlags = D3D11_CPU_ACCESS_READ;
    desc.Usage = D3D11_USAGE_STAGING;
    desc.MiscFlags = 0;
    desc.SampleDesc.Count = 1;
    desc.SampleDesc.Quality = 0;
    desc.MipLevels = 1;
    desc.ArraySize = 1;
    staging_.Reset();
    if (FAILED(device->CreateTexture2D(&desc, nullptr, &staging_))) return false;
    width_ = desc.Width;
    height_ = desc.Height;
    pixels_.resize(static_cast<size_t>(width_) * height_ * 4);
    return true;
}

bool GameCapture::capture(IDXGISwapChain* swap_chain) {
    if (!connected() || !swap_chain) return false;
    Microsoft::WRL::ComPtr<ID3D11Texture2D> back_buffer;
    if (FAILED(swap_chain->GetBuffer(0, IID_PPV_ARGS(&back_buffer))) ||
        !update_staging(swap_chain, back_buffer.Get())) return false;
    Microsoft::WRL::ComPtr<ID3D11Device> device;
    if (FAILED(swap_chain->GetDevice(IID_PPV_ARGS(&device)))) return false;
    Microsoft::WRL::ComPtr<ID3D11DeviceContext> context;
    device->GetImmediateContext(&context);
    context->CopyResource(staging_.Get(), back_buffer.Get());
    D3D11_MAPPED_SUBRESOURCE mapped{};
    if (FAILED(context->Map(staging_.Get(), 0, D3D11_MAP_READ, 0, &mapped))) return false;
    const size_t stride = static_cast<size_t>(width_) * 4;
    for (unsigned int row = 0; row < height_; ++row)
        std::memcpy(pixels_.data() + row * stride,
                    static_cast<const std::uint8_t*>(mapped.pData) + row * mapped.RowPitch, stride);
    context->Unmap(staging_.Get(), 0);

    const FrameHeader header{{'M','M','G','C'}, htons(static_cast<std::uint16_t>(source_.size())),
        htons(static_cast<std::uint16_t>(token_.size())), htonl(width_), htonl(height_),
        htonl(static_cast<std::uint32_t>(pixels_.size()))};
    return send_all(&header, sizeof(header)) && send_all(source_.data(), source_.size()) &&
           send_all(token_.data(), token_.size()) && send_all(pixels_.data(), pixels_.size());
}
