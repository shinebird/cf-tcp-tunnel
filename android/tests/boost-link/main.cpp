extern "C" bool cf_json_roundtrip();
int main() { return cf_json_roundtrip() ? 0 : 1; }
