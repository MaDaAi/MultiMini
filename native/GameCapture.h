#pragma once

#include <winsock2.h>
#include <d3d11.h>
#include <dxgi.h>
#include <wrl/client.h>
#include <cstdint>
#include <string>
#include <vector>

// Explicit opt-in DX11 swap-chain capture for the controlled test renderer.
// Commercial games require a separate, consent-based present-hook adapter.
class GameCapture {
public:
    GameCapture(unsigned short port, std::string source, std::string token);
    ~GameCapture();
    GameCapture(const GameCapture&) = delete;
    GameCapture& operator=(const GameCapture&) = delete;

    bool connected() const { return socket_ != INVALID_SOCKET; }
    bool capture(IDXGISwapChain* swap_chain);

private:
    bool send_all(const void* bytes, size_t count);
    bool update_staging(IDXGISwapChain* swap_chain, ID3D11Texture2D* back_buffer);

    SOCKET socket_ = INVALID_SOCKET;
    std::string source_;
    std::string token_;
    Microsoft::WRL::ComPtr<ID3D11Texture2D> staging_;
    std::vector<std::uint8_t> pixels_;
    unsigned int width_ = 0;
    unsigned int height_ = 0;
};
