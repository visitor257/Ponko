#include "vocab.h"

#include "clip_merges.hpp"

// Ponko 精简版：SD1.5 只需要 CLIP tokenizer。
// 其余 tokenizer 的词表（gemma/gemma2/gpt_oss/mistral/qwen2/t5/umt5）合计约 250 MB 源码，
// 在同一个 TU 里编译会撑爆内存，这里改为返回空串（SD1.5 路径不会调用它们）。

std::string load_clip_merges() {
    std::string merges_utf8_str(reinterpret_cast<const char*>(clip_merges_utf8_c_str),
                                sizeof(clip_merges_utf8_c_str));
    return merges_utf8_str;
}

std::string load_qwen2_merges() { return {}; }

std::string load_mistral_merges() { return {}; }
std::string load_mistral_vocab_json() { return {}; }

std::string load_t5_tokenizer_json() { return {}; }
std::string load_umt5_tokenizer_json() { return {}; }

std::string load_gemma_merges() { return {}; }
std::string load_gemma_vocab_json() { return {}; }
std::string load_gemma2_merges() { return {}; }
std::string load_gemma2_vocab_json() { return {}; }
std::string load_gpt_oss_merges() { return {}; }
std::string load_gpt_oss_vocab_json() { return {}; }
