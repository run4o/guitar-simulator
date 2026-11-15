#pragma once

#include <cstdint>
#include <cstdio>
#include <cstring>

namespace gte {

/*
 * Minimal 16-bit PCM mono WAV writer (RIFF). Used by the offline render API
 * for CI verification and audio export.
 *
 * Note: fopen is ANSI on Windows — non-ASCII output paths are unsupported
 * for now (test paths are ASCII); switch to _wfopen if that ever matters.
 */
class WavWriter {
public:
    WavWriter(const char* path_utf8, int sample_rate) : sample_rate_(sample_rate) {
        file_ = std::fopen(path_utf8, "wb");
        if (!file_) return;

        const uint32_t byte_rate = static_cast<uint32_t>(sample_rate) * 2u;
        std::fwrite("RIFF", 1, 4, file_);
        write_u32(0); // RIFF size, patched in finalize()
        std::fwrite("WAVE", 1, 4, file_);
        std::fwrite("fmt ", 1, 4, file_);
        write_u32(16);          // PCM chunk size
        write_u16(1);           // PCM
        write_u16(1);           // mono
        write_u32(static_cast<uint32_t>(sample_rate));
        write_u32(byte_rate);
        write_u16(2);           // block align
        write_u16(16);          // bits per sample
        std::fwrite("data", 1, 4, file_);
        write_u32(0);           // data size, patched in finalize()
    }

    ~WavWriter() { finalize(); }

    bool ok() const { return file_ != nullptr; }

    void write(float mono) {
        if (!file_) return;
        float clamped = mono;
        if (clamped > 0.98f) clamped = 0.98f;
        if (clamped < -0.98f) clamped = -0.98f;
        auto s = static_cast<int16_t>(clamped * 32767.0f);
        uint8_t bytes[2] = {
            static_cast<uint8_t>(static_cast<uint16_t>(s) & 0xFFu),
            static_cast<uint8_t>((static_cast<uint16_t>(s) >> 8) & 0xFFu)
        };
        std::fwrite(bytes, 1, 2, file_);
        data_bytes_ += 2;
    }

    void finalize() {
        if (!file_) return;
        // RIFF chunk size = 4 ("WAVE") + 24 (fmt) + 8 (data header) + data
        patch_u32(4, 36u + data_bytes_);
        patch_u32(40, data_bytes_);
        std::fclose(file_);
        file_ = nullptr;
    }

private:
    void write_u32(uint32_t v) {
        uint8_t b[4] = { uint8_t(v & 0xFF), uint8_t((v >> 8) & 0xFF),
                         uint8_t((v >> 16) & 0xFF), uint8_t((v >> 24) & 0xFF) };
        std::fwrite(b, 1, 4, file_);
    }
    void write_u16(uint16_t v) {
        uint8_t b[2] = { uint8_t(v & 0xFF), uint8_t((v >> 8) & 0xFF) };
        std::fwrite(b, 1, 2, file_);
    }
    void patch_u32(long offset, uint32_t v) {
        uint8_t b[4] = { uint8_t(v & 0xFF), uint8_t((v >> 8) & 0xFF),
                         uint8_t((v >> 16) & 0xFF), uint8_t((v >> 24) & 0xFF) };
        std::fseek(file_, offset, SEEK_SET);
        std::fwrite(b, 1, 4, file_);
        std::fseek(file_, 0, SEEK_END);
    }

    std::FILE* file_ = nullptr;
    int sample_rate_;
    uint32_t data_bytes_ = 0;
};

} // namespace gte
