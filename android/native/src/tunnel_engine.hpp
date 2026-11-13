#pragma once
#include <array>
#include <cstdint>
#include <functional>
#include <memory>
#include <string>
#include <vector>

namespace cf_native {
// One Engine per service generation. run() blocks on the calling worker thread.
// Only stop() and stats() may be called from another thread. No traffic crosses JNI.
class Engine final {
public:
    using Logger = std::function<void(std::string const&)>;
    Engine(std::vector<std::string> arguments, std::string ca_pem);
    ~Engine();
    Engine(Engine const&) = delete;
    Engine& operator=(Engine const&) = delete;
    int run(Logger logger);
    void stop() noexcept;
    std::array<std::uint64_t, 3> stats() const noexcept;
    static std::string version();
private:
    struct Impl;
    std::unique_ptr<Impl> p_;
};
}
