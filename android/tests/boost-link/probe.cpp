#include <boost/json.hpp>
#include <string>
extern "C"
#if defined(_WIN32)
__declspec(dllexport)
#endif
bool cf_json_roundtrip() {
    for (double x : {0.0, -0.0, 0.1, -1234.5678, 1e-200, 1e200}) {
        boost::json::value v(x);
        auto parsed = boost::json::parse(boost::json::serialize(v));
        double result = parsed.is_double() ? parsed.as_double() :
            parsed.is_int64() ? static_cast<double>(parsed.as_int64()) :
                               static_cast<double>(parsed.as_uint64());
        if (result != x) return false;
    }
    return true;
}
