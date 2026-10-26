# Welly offline brain: can the TV run its own model?

Research for adding a third, on-TV brain to Welly (WELLY-HOME-REMOTE-SPEC.md §1.1–1.2, §3.2; `WellyBrain.java`).
Target: the 85-inch TCL Google TV bought at Costco (US, model years 2023–2025; exact model not yet known).
Written 2026-10-26.

Each claim carries a tag. **[documented]** means a vendor doc, model card or source code. **[observed]** means a
measurement, an HTTP response we checked, or several independent user reports. **[unverified]** means a single forum
or blog source. **[inferred]** means our own reasoning. **[estimate]** means a number we derived, with the math shown.

---

## 0. Summary

| Question | Answer |
|---|---|
| Can the TV run an LLM at all? | Yes, a 0.3–0.6B model, slowly: about **4–8 tok/s decode, 30–60 tok/s prefill** on 4×A73 @ 1.4 GHz [estimate, §2.3]. |
| Is it useful for Welly? | Only for short home-control turns. With today's ~2,000-token Welly prompt, the first token takes **30–60 s** [estimate]. A cut-down prompt with a cached prefix gets a "turn on my lamp" turn to **~8–12 s**. |
| Biggest blocker | The TV's **userspace is probably 32-bit** (armeabi-v7a), like most current Google TVs [documented: Google's 64-bit TV transition, §1.4]. That rules out LiteRT-LM and MLC-LLM, which ship arm64 only, and leaves llama.cpp built for armv7. |
| RAM | 2–4 GB total, of which roughly **0.6–1.2 GB** is realistically free with the home screen open [inferred]. The model process must be the one lmkd kills, never `:home`. |
| Engine | **llama.cpp `llama-server`**, run as a child process from `nativeLibraryDir` and bound to 127.0.0.1. `WellyBrain` then adds one more OpenAI-compatible `Brain`, with no JNI and no Kotlin. |
| Model | **Qwen3-0.6B Q4_K_M** (397 MB, Apache-2.0, BFCL v3 44.1). Second choice: Qwen3.5-0.8B Q4_K_M (533 MB). Later, a fine-tuned **FunctionGemma 270M** (253–292 MB). |
| Download size | ~**400 MB**, not 800 MB. An 800 MB model (Gemma 3 1B, Llama 3.2 1B) runs at about 2–4 tok/s on this chip, which is too slow. |
| Build the downloader now? | **No.** Build the zero-model **Tier 0 intent parser** and the router first (~2 days). Run the device probe (§1.6). Build the downloader and the on-TV brain only if the probe passes (arm64, or armv7 with ≥ 1 GB `MemAvailable`). |
| Side finding (**fixed** since: build.sh now generates `res/xml/network_security_config.xml` allowing plain HTTP only to 127.0.0.1, localhost and `ollama_url`'s host) | `AndroidManifest.xml` sets `android:usesCleartextTraffic="false"` and the app has no network-security config. So **the Ollama tier at `http://<mac-ip>:11434` cannot work on the TV today**: `HttpURLConnection` refuses cleartext and `WellyBrain` silently skips that brain [inferred from code + documented Android behaviour]. A local `llama-server` on `http://127.0.0.1` would hit the same wall. §3.6 has the fix. |

---

## 1. TCL 85" Google TV hardware reality check

### 1.1 Which chip is in which TCL (US) model

TCL's US spec pages name a "AIPQ Processor" (picture processing) but never the SoC, RAM or eMMC [observed: us.tcl.com
QM6K/QM7K/QM8K pages]. The mapping below comes from firmware platform IDs and reviews.

| US model (year) | Platform ID | SoC | CPU | GPU | Source |
|---|---|---|---|---|---|
| QM8 (2023) | T615 | MediaTek **MT9615** | 4× Cortex-A73 @ ~1.3–1.6 GHz | Mali-G52 class | AVS owners thread lists "MT9615 … Cortex A-73x4 1.3GHz" [unverified]; gadgetversus lists 4 cores, 1.6 GHz turbo [unverified] |
| Q7 (2023) | T615 (ambiguous) | MT9615 | as above | as above | TCL Telegram chipset table [unverified] |
| QM8 / QM851G, QM7 / QM751G (2024) | T653T01/T02 | **Pentonic 700** (MT9653 / MT9618; FlatpanelsHD calls it "MT5896") | **4× Cortex-A73 @ 1.4 GHz** | **Mali-G52 MC1** | Wikipedia MediaTek list [documented-ish]; Tab-TV [unverified]; FlatpanelsHD C855/C8K reviews [observed] |
| Q651G, S551G, Q750G (2024) | T653T02 **or** T800T02 **or** R75P | Pentonic 700, **or** Amlogic T963D4 (5× A55-class @ 1.9 GHz, Mali-G57 MC1, **2 GB DDR4**), **or** Realtek RT75 | — | — | The chipset table lists the same model names under three platforms, so the chip varies by size and region [unverified] |
| **QM8K, QM7K, QM6K, Q77K, QM67K (2025)** | T653T02 | **Pentonic 700** (same chip as 2024, per FlatpanelsHD's C8K review) | 4× A73 @ 1.4 GHz | Mali-G52 MC1 | FlatpanelsHD C8K review: "same MediaTek Pentonic 700 (MT5896)", Google TV on **Android 12**, **48 GB free** storage [observed] |
| QM9K (2025) | T653T03 | Pentonic 700 "flagship" variant | — | — | [unverified] |
| QM8L / QM7L (2026) | T655T01 | Pentonic 800 (MT9655): 4× A73 @ 1.8 GHz, Mali-G57 MC1, 64-bit DDR4 = 25.6 GB/s | — | — | Wikipedia [documented-ish]; AVS posts disagree on whether QM7L is 700 or 800 [unverified] |

Costco US currently lists the 85" **QM6K Pro** and the 85" **Q77K** [observed: costco.com listings]. Both are on the T653T02
(Pentonic 700) platform per the chipset table. **Planning assumption: Pentonic 700, 4× A73 @ 1.4 GHz, Mali-G52 MC1,
Google TV on Android 12 or 14** (FlatpanelsHD saw Android 12 on the C8K; this repo's CLAUDE.md notes a 2024 MediaTek TCL Google TV on Android 14) [inferred]. Fallback assumption: an Amlogic T963D4 with 2 GB, which is worse on RAM but
better on instructions (§1.3).

### 1.2 RAM, storage and memory bandwidth

| Item | Value | Tag |
|---|---|---|
| RAM, Pentonic 700 sets | 3 GB DDR4-2666 (2023 MT9615 QM8) and **4 GB DDR4-3200** (Pentonic 700 variant) reported by an owner; "only China models have 4GB" according to another | [unverified]; treat as **3 GB likely, 4 GB possible** |
| RAM, Amlogic/Realtek budget sets | 2 GB | [unverified] |
| Google TV minimum | 2 GB (Android TV can certify 1–1.5 GB "low-RAM" devices; Google TV keeps 2 GB) | [documented-ish: Android Authority / Android Police reading AOSP Gerrit] |
| eMMC | 64 GB on the 2024–2025 Pentonic 700 sets (48 GB free after setup); 32 GB on the 2023 QM8; 16 GB on budget sets | [observed: FlatpanelsHD] / [unverified] |
| Memory bandwidth | Pentonic 800 is 64-bit DDR4 @ 3200 MT/s = 25.6 GB/s. The Pentonic 700 width isn't published; a 32-bit bus at 3200 MT/s gives **12.8 GB/s peak** | [documented for 800] / [inferred for 700] |
| Usable bandwidth for the CPU | ~**4–6 GB/s**. An A73 cluster achieves ~40–50 % of peak, and the 4K UI, panel timing controller and MEMC share the same DRAM | [estimate] |

How much of the 3 GB an app can use [inferred]: TV SoCs reserve large carve-outs (secure video, 4K frame buffers, MEMC,
TCON). A "3 GB" set typically shows `MemTotal` around 2.2–2.6 GB. Google TV, Play services, the TV input framework,
TCL's services and our `:home` then leave about **0.6–1.2 GB `MemAvailable`** with the home screen open. That window
fits a ~400 MB model plus ~100 MB of KV cache. It does not fit a 1.1 GB model plus KV. Measure it (§1.6).

### 1.3 CPU features that matter for inference

| Core | ISA | Dot product (`sdot`/`udot`) | i8mm | FP16 arithmetic | Notes |
|---|---|---|---|---|---|
| **Cortex-A73** (Pentonic 700/800, MT9615) | ARMv8.0-A | **no** | no | no | llama.cpp gates its fast repacked Q4_0 kernels (`q4_0_4x4`) on dotprod, so A73 falls back to plain NEON kernels [documented: llamafile issue #980 quoting `ggml_repack_get_optimal_repack_type`]. An ARMv8.0 binary must never be built with `+dotprod` or it will SIGILL [observed]. |
| Cortex-A55 (Amlogic T963D4, MT9612/9638) | ARMv8.2-A | **yes** | no | yes | Narrow in-order core, but dotprod roughly doubles int8 matmul throughput per clock [inferred] |
| Cortex-A76 (Pi 5, Pentonic 2000) | ARMv8.2-A | yes | no | yes | Our benchmark reference point |

**NPU/APU.** MediaTek markets an "AI processing unit" in the Pentonic 700 for AI-Super Resolution and scene and object
recognition in picture quality [documented: MediaTek press release]. No TOPS figure is published, and apps cannot reach
it. TV vendor images expose no NNAPI driver for it, NNAPI itself is deprecated, and LiteRT's MediaTek NPU builds exist
only for Dimensity phone chips (`…_mt6989/6991/6993.litertlm` in litert-community) [observed: HF file listings]
[inferred: no app access]. **Treat the NPU as non-existent.**

**GPU.** The Mali-G52 MC1 is a single Bifrost core without fast FP16. llama.cpp on small Mali GPUs (Vulkan/OpenCL) is
repeatedly reported as *slower* than the CPU [observed: llama.cpp #5965, #9464; Mali-G52 issue in Galaxy-Android].
LiteRT-LM's GPU path needs `libOpenCL.so` through `<uses-native-library>`, which TV images often don't expose
[inferred]. The GPU is also busy compositing our 60 fps home screen. **No GPU offload.**

### 1.4 ABI: is the TV's userspace 32-bit?

- In August 2025 Google announced that from **1 Aug 2026**, TV apps with native code must ship arm64 *alongside*
  armeabi-v7a, "to support upcoming 64-bit TV devices". It also said Play "will continue to deliver apps to 32-bit
  devices" [documented: Android Developers Blog]. The announcement only makes sense if current Google TVs ship a
  **32-bit userspace on 64-bit CPUs**. FlatpanelsHD and AFTVnews read it the same way [observed].
- TCL owners report `ro.vendor.product.cpu.abilist = armeabi-v7a,armeabi` and "BSP is only 32 bit" on TCL sets
  [unverified: Kodi forum, AVS]. 32-bit-only Google TVs reject arm64-only APKs with `INSTALL_FAILED_NO_MATCHING_ABIS`
  [observed: several GitHub issues].
- **Assume armeabi-v7a userland until `getprop ro.product.cpu.abilist` says otherwise** [inferred].

What that means for us:

| Engine | arm64-v8a | armeabi-v7a |
|---|---|---|
| LiteRT-LM (`litertlm-android`) | yes (`jni/arm64-v8a/liblitertlm_jni.so`, 21.5 MB) | **no**. LiteRT Next ships 64-bit only [observed: LiteRT issue #3926] |
| MediaPipe LLM Inference (`tasks-genai`) | yes | no; also deprecated / maintenance-only [documented] |
| MLC-LLM | yes | **no**; 32-bit builds fail at link time [observed: mlc-llm #2864] |
| ONNX Runtime GenAI | yes | buildable (`--android_abi armeabi-v7a`) [documented], but no prebuilt AAR and the model zoo is Phi-centric |
| **llama.cpp** | yes, with a prebuilt `llama-bXXXX-bin-android-arm64.tar.gz` (77 MB) on every release [observed: GitHub releases API, b11906 of 2026-10-26] | **yes, built yourself**: NDK with `-DANDROID_ABI=armeabi-v7a` (fix merged in PR #5702) [documented]. Expect ~20–40 % slower than arm64 on the same core: 16 NEON registers instead of 32 and less-tuned kernels [inferred] |

A 32-bit process also has at most ~3–4 GB of address space. That is fine for a 0.4–1.1 GB `mmap` [inferred].

### 1.5 Memory management: lmkd, oom_adj, and keeping `:home` alive

| Mechanism | Fact | Tag |
|---|---|---|
| lmkd | Userspace daemon; PSI-based since Android 10 (`ro.lmk.use_psi=true`). At *medium* pressure it kills processes with `oom_score_adj ≥ 800` (`ro.lmk.medium`); at *critical* pressure it kills anything ≥ 0 | [documented: AOSP lmkd] |
| `ro.config.low_ram` | Switches lmkd to low-RAM tuning (`psi_partial_stall_ms` 200 instead of 70, `thrashing_limit` 30 instead of 100, …). Apps see it as `ActivityManager.isLowRamDevice()`. The TV memory guide caps apps on 1 GB low-RAM TVs at 280 MB total | [documented: AOSP lmkd; developer.android.com/training/tv/playback/memory] |
| oom_adj ladder | FOREGROUND 0 · VISIBLE 100 · PERCEPTIBLE 200 · SERVICE 500 · **HOME 600** ("try to avoid killing… even in the background") · PREVIOUS 700 · SERVICE_B 800 · CACHED 900–999 | [documented: `ProcessList.java`, AOSP main] |
| Consequence for us | While a video app is in front, `:home` sits at **600**. Any process of ours at a *lower* number, such as a foreground service (200) or a started service (500), is treated as *more important than the launcher*. The model process must never hold a foreground service | [inferred] |
| Native children | A process forked by an app inherits the parent's `oom_score_adj` at fork time. AMS/lmkd later update only the app's own pid, so the child keeps that number. When the app dies, AMS kills the whole process group, child included | [inferred from AOSP behaviour; verify with `cat /proc/<pid>/oom_score_adj`] |
| Phantom process killer (Android 12+) | Child processes of apps are "phantom processes". Android caps them at 32 system-wide and kills those using excessive CPU while their app is in the background. These TVs run Android 12 | [observed: Termux #2366, Google issue 205156966] |
| Cached-app freezer | Frozen cached processes take their cgroup's children with them. A model process left "cached" mid-answer would freeze | [inferred] |
| `largeHeap` | Raises the *Java* heap limit only. Native `malloc` and `mmap` (llama.cpp weights, KV cache) are outside the Java heap and not limited by `dalvik.vm.heapsize`, but lmkd still sees them in RSS/PSS | [documented / inferred] |
| `mmap` weights | GGUF weights mapped read-only are **file-backed, clean pages**. Under pressure the kernel can drop them without killing anyone, then re-read them from eMMC, which is slow but not fatal. Only the KV cache and compute buffers are anonymous memory | [inferred]; this favours llama.cpp's default mmap and argues against `--mlock` |

**Design rule [inferred]:** the model runs *only while `:home` is in the foreground*, so it never competes with a video
app. It lives in its own process, and that process is the first thing to go. §4.4 gives the exact policy.

### 1.6 How to identify your TV (run once, paste the output into this doc)

```bash
adb connect <tv-ip>:5555
adb shell getprop ro.product.model              # e.g. 85QM6K, 85Q77K
adb shell getprop ro.board.platform             # MediaTek: m7332 / mt5896 / mt9653…; Amlogic: t963…
adb shell getprop ro.product.cpu.abilist        # "armeabi-v7a,armeabi" = 32-bit userland; contains arm64-v8a = 64-bit
adb shell getprop ro.vendor.product.cpu.abilist
adb shell getprop ro.build.version.release      # 12 or 14
adb shell getprop ro.config.low_ram             # true = low-RAM tuning: stop here, no on-TV model
adb shell 'getprop | grep ro.lmk'
adb shell cat /proc/cpuinfo                     # "CPU part": 0xd09 = A73, 0xd05 = A55, 0xd08 = A72, 0xd0b = A76
                                                # "Features": asimddp = dot product; "v7l" processor name = 32-bit kernel too
adb shell cat /proc/meminfo | head -5           # MemTotal, MemAvailable (run with Free My TV's home screen open)
adb shell df -h /data
adb shell dumpsys meminfo | tail -12            # Total RAM / Free RAM / Used / Lost
adb shell dumpsys meminfo io.freemytv:home | head -30
adb shell 'for p in $(pidof io.freemytv:home); do cat /proc/$p/oom_score_adj; done'   # 0 in front, 600 behind a video app
adb shell device_config get activity_manager max_phantom_processes
```

**Go / no-go for an on-TV model [inferred]:** `low_ram` false, **and** `MemAvailable ≥ 1.0 GB` with the home screen
open, **and** `/data` free ≥ 2 GB. The ABI then picks the engine build (§3).

---

## 2. Model feasibility

### 2.1 Candidates (sizes checked against the Hugging Face API on 2026-10-26)

| Model | Params | Q4_K_M GGUF | Other format | Licence | Tool calling | Languages | Verdict |
|---|---|---|---|---|---|---|---|
| **FunctionGemma 270M** (Dec 2025) | 0.27B | 253 MB (Q8_0 292 MB) | `.litertlm` q8 289 MB | Gemma terms | Purpose-built. BFCL simple 61.6 / multiple 63.5 / live-simple 36.2; "Mobile Actions" 58 % base → **85 % fine-tuned**; "not a direct dialogue model" | English evals only | Best fit for tool routing **after fine-tuning** on Welly's tools; can't chat [documented: model card] |
| SmolLM2-360M-Instruct | 0.36B | 271 MB | — | Apache-2.0 | weak | English | Too weak for tools [inferred] |
| **Qwen3-0.6B** | 0.6B | **397 MB** | `.litertlm` 345–614 MB | Apache-2.0 | Native (Hermes-style). **BFCL v3 44.1** non-thinking | 119 | **Primary pick** [documented: Qwen3 tech report] |
| Qwen3.5-0.8B (Mar 2026) | 0.8B (+vision) | 533 MB | — | Apache-2.0 | Native; BFCL-V4 25.3 (thinking; V4 ≠ V3, not comparable); IFEval 52.1 non-thinking; "prone to thinking loops" | 201 | Alternative. Hybrid Gated-DeltaNet layers give a tiny KV cache, but the arch is newer in llama.cpp and the 248k vocab makes the output head heavy [documented: model card] |
| Gemma 3 1B | 1.0B | 806 MB (Q4_0 722) | `.litertlm` 584 MB (gated repo) | Gemma terms | No native tools; BFCL v3 **16.3** | weaker below 4B [unverified] | No [documented: Qwen3 report table] |
| Llama 3.2 1B Instruct | 1.2B | 808 MB | — | Llama 3.2 licence | BFCL v2 25.7 (Meta card); "breaks schemas even at Q8_0" in one test | 8 | No [documented / unverified] |
| Qwen3-1.7B | 1.7B | 1,107 MB | — | Apache-2.0 | **BFCL v3 52.2** | 119 | Only on a 4 GB arm64 set, and still ~2 tok/s |
| SmolLM2-1.7B | 1.7B | 1,056 MB | — | Apache-2.0 | some | English | No: MHA makes the KV cache 4× bigger |
| Qwen3.5-2B | 2B | 1,281 MB | — | Apache-2.0 | BFCL-V4 43.6 (thinking) | 201 | Too big and slow here |
| Gemma 3n E2B | ~5B raw / "2B effective" | 3,027 MB | `.litertlm` 3,656 MB | Gemma terms | yes | 140 | No: file too big |
| Gemma 4 E2B (Apr 2026, Apache-2.0) | "2B effective" | 3,107 MB | `.litertlm` **2,588 MB** (text weights "as low as 0.8 GB" + 1.12 GB mmapped embeddings) | Apache-2.0 | Native function calling | 140+ | No: Pi 5 does only 7.6 tok/s (§2.3) [documented: HF card] |
| MobileLLM-R1 950M / 360M | 0.95B | no official GGUF | safetensors 3.8 GB | **FAIR non-commercial**, gated | none | English | No: math/code reasoner, "not general-purpose chat" [documented] |

### 2.2 RAM footprint at 2k context

KV bytes per token = 2 (K, V) × layers × KV heads × head dim × 2 bytes (f16).

| Model | Weights (mmap) | KV/token | KV @ 2,048 (f16) | KV @ 1,024 | Runtime + compute buffers | **Total resident (2k)** |
|---|---|---|---|---|---|---|
| FunctionGemma 270M (18 L × 1 KV × 256) | 253–292 MB | 18 KiB | 36 MiB | 18 MiB | ~60 MB | **~0.4 GB** |
| Qwen3-0.6B (28 L × 8 KV × 128) | 397 MB | 112 KiB | 224 MiB (q8_0 KV: 119) | 112 MiB | ~80 MB | **~0.7 GB** (0.6 GB at 1k ctx) |
| Qwen3.5-0.8B (6 attention L × 2 KV × 256; 18 DeltaNet L) | 533 MB | 12 KiB + ~19 MB fixed state | ~43 MiB | ~31 MiB | ~100 MB | **~0.7 GB** |
| Gemma 3 1B (26 L × 1 KV × 256) | 806 MB | 26 KiB | 52 MiB | 26 MiB | ~100 MB | ~0.95 GB |
| Llama 3.2 1B (16 L × 8 KV × 64) | 808 MB | 32 KiB | 64 MiB | 32 MiB | ~100 MB | ~1.0 GB |
| Qwen3-1.7B (28 L × 8 KV × 128) | 1,107 MB | 112 KiB | 224 MiB | 112 MiB | ~120 MB | ~1.45 GB |
| SmolLM2-1.7B (24 L × 32 KV × 64) | 1,056 MB | 192 KiB | 384 MiB | 192 MiB | ~120 MB | ~1.55 GB |

[estimate: layer and head counts from the model configs; runtime overhead from typical llama.cpp `llama_context` logs.]
Against a 0.6–1.2 GB `MemAvailable`, **only the ≤ 0.6B row fits with margin.**

### 2.3 Speed on 4× A73 @ 1.4 GHz: benchmarks, then the math

Measured reference points:

| Device | Model / engine | Prefill | Decode | Tag |
|---|---|---|---|---|
| Raspberry Pi 5 (4× A76 @ 2.4 GHz, dotprod) | Qwen2.5-0.5B-class Q4_0, llama.cpp | **290 tok/s** (4 threads), 174 (2 t) | **27–34 tok/s** (peaks at **2 threads**: 33.8 vs 27.3 at 4) | [observed: llama.cpp discussion #30069] |
| Raspberry Pi 5 | Gemma 4 E2B, LiteRT-LM CPU, 4 threads | 133 tok/s | 7.6 tok/s, TTFT 7.8 s (1,024-token prompt) | [documented: HF card] |
| Raspberry Pi 5 | 1B Q4_K_M, Ollama | — | ~8–12 tok/s | [unverified] |
| Raspberry Pi 4 (4× **A72 @ 1.8 GHz**, LPDDR4-3200, no dotprod) | Q4_K_M, Ollama | — | ≤135M: >15 tok/s · 135–360M: 5–15 · **≥1B: <5 tok/s** | [documented: arXiv 2511.07425] |
| Samsung S26 Ultra CPU | Gemma 4 E2B, LiteRT-LM | 557 | 46.9 | [documented] |
| Samsung S25 Ultra CPU | FunctionGemma 270M q8, LiteRT | 1,718 | 125.9 | [documented] |

**The Pi 4 is the closest proxy.** A72 and A73 have similar IPC and both lack dotprod. The TV clocks 1.4 vs 1.8 GHz
(×0.78), shares DRAM with the 4K UI, and probably runs armv7 code (×0.6–0.8).

**Decode is memory-bound:** tok/s ≤ usable bandwidth ÷ bytes read per token. The bytes read are about the whole file,
because the tied embedding matrix is also the output head and is read in full every token.

| Model | Bytes/token | Bandwidth bound @ 4–6 GB/s | Compute bound (Pi 4 band × 0.78 × armv7 0.7) | **Expected decode** | Expected prefill |
|---|---|---|---|---|---|
| FunctionGemma 270M Q8 | 0.29 GB | 14–21 tok/s | ~6–10 | **6–10 tok/s** | 80–150 tok/s |
| Qwen3-0.6B Q4_K_M | 0.40 GB | 10–15 | ~4–7 | **4–7 tok/s** | **30–60 tok/s** |
| Qwen3.5-0.8B Q4_K_M | 0.53 GB | 7.5–11 | ~3–6 | **3–6 tok/s** | 25–50 |
| Gemma 3 1B / Llama 3.2 1B Q4_K_M | 0.81 GB | 5–7.5 | ~2–3.5 | **2–3.5 tok/s** | 15–30 |
| Qwen3-1.7B Q4_K_M | 1.11 GB | 3.6–5.4 | ~1.3–2.5 | **1.3–2.5 tok/s** | 10–18 |
| Gemma 4 E2B (LiteRT, arm64 only) | ~1 GB active | ~4–6 | Pi 5's 7.6 ÷ (2.4/1.4 clock × ~1.5 IPC) ≈ 3 | **~2–3 tok/s** | ~30–50 (133 ÷ ~3–4) |

[estimate.] Prefill is compute-bound. The Pi 5 does 290 tok/s on 0.5B. Divide by 1.7 for clock, ~1.5 for A76-vs-A73
IPC and ~2–2.5 for no dotprod / no repacked kernels: **~40–60 tok/s for 0.5–0.6B**, lower again for armv7.

### 2.4 What that means for one Welly turn

The current Welly prompt (`WellyBrain.system()` + 16 tool schemas + up to 20 memory turns) is about **2,000–2,500
tokens** [estimate: ~1,600 chars of system text ≈ 400 tokens; the tools JSON ≈ 1,100–1,300; memory 300–800].

| Scenario (Qwen3-0.6B, prefill 40 tok/s, decode 5 tok/s) | Prefill | Tool call (~25 tok) | 2nd round (tool result ~60 tok + 20-token reply) | **Total** |
|---|---|---|---|---|
| Today's full prompt, no cache | 2,200 → **55 s** | 5 s | 1.5 s + 4 s | **~65 s**: unusable |
| Cut prompt (~550 tok: 6 tools, short system, 2 turns), no cache | 14 s | 5 s | 5.5 s | ~25 s |
| Cut prompt, **static prefix cached** (only ~60 new tokens per turn) | 1.5 s | 5 s | 5.5 s | **~12 s** |
| Same, but Tier 3 skips the 2nd round for home tools (templated "Lamp's on.") | 1.5 s | 5 s | — | **~6.5 s** |
| **Tier 0 regex** ("turn on my lamp") | — | — | — | **< 0.1 s + Kasa round trip** |

[estimate.] This table is the core of the recommendation. An on-TV LLM is a 6–12 s brain for things a 50-line parser
does instantly.

### 2.5 Tool-calling and JSON reliability

- **Below 1B, general models fail on tools unless constrained.** Llama-3.2-1B scored 10.8 on BFCL v4, versus 30.4 for
  a 1B model trained for function calling [unverified: BFCL write-up]. Qwen3-0.6B "keeps emitting valid schemas all the
  way to Q4, but starts returning wrong arguments" [unverified: dev.to BFCL run].
- llama-server with `--jinja` parses Qwen/Hermes tool calls and constrains the call's JSON with a lazy grammar
  [documented: llama.cpp `docs/function-calling.md`]. That removes *malformed* JSON but not *wrong* arguments. The fix
  for wrong arguments is to make them enums (`room ∈ {parents, mine, living, all}`), which the grammar then enforces
  [inferred].
- Qwen3 needs thinking off: Welly already appends `/no_think` for `ollama:qwen3`. Do the same for the local brain, or
  start the server with `--chat-template-kwargs '{"enable_thinking":false}'`. Qwen3.5 small models default to
  non-thinking but can print tool calls inside a think block if thinking is on [observed: llama.cpp #20837].
- **Multilingual:** the app ships Russian strings (`values-ru`), and Welly answers in `Ui.language`. Qwen3 (119
  languages) and Qwen3.5 (201) are the only small candidates with a real claim here; SmolLM2 is English-only and
  FunctionGemma was evaluated in English only [documented].

---

## 3. Integration into the zero-Gradle repo

`build.sh` today does aapt2 compile/link → javac (Java 8, `-bootclasspath android.jar`) → d8 → `zip -qj base.apk
classes.dex` → zipalign → apksigner. There is no native code, no dependency resolution, and the APK is 2.6 MB.
Processes: main, `:home` (home, settings, screensaver), `:keys`, `:sleep`.

### 3.1 Option B (recommended): `llama-server` as a child process, OpenAI API on 127.0.0.1

Why it fits:

- It speaks the same wire format as Gemini and Ollama, so `WellyBrain.chain()` gains one `Brain`. No JNI and no
  Kotlin. `converse()` and the tool loop stay unchanged.
- It is the only mature engine that builds for **armeabi-v7a**.
- GGUF weights are `mmap`ed as clean pages (§1.5). The Hugging Face mirrors (unsloth/bartowski) are ungated, so the
  TV can download without a token.

**Exec rules (Android 10+ W^X):** apps targeting API ≥ 29 cannot `execve()` files under `getFilesDir()`. A file
packaged as a native library and extracted to `ApplicationInfo.nativeLibraryDir` *can* be executed [documented:
Android 10 behaviour changes]. So name the binary `libwellybrain.so`, put it in `lib/<abi>/`, and set
`android:extractNativeLibs="true"`. Without AGP the attribute isn't set for you, so set it explicitly. Link it
statically (`ANDROID_STL=c++_static`, `BUILD_SHARED_LIBS=OFF`) so no `LD_LIBRARY_PATH` is needed [observed: W^X
write-ups]. Downloading an executable at run time is the pattern Play flags as malware, so ship the binary in the APK
and download only the weights [observed].

Build once with the NDK (a new `tools/build-llama.sh`, run by hand like the other tools):

```bash
# ABI from `getprop ro.product.cpu.abilist`: armeabi-v7a (likely) or arm64-v8a
cmake -S llama.cpp -B build-tv -DCMAKE_TOOLCHAIN_FILE=$NDK/build/cmake/android.toolchain.cmake \
  -DANDROID_ABI=armeabi-v7a -DANDROID_ARM_NEON=ON -DANDROID_PLATFORM=android-26 -DANDROID_STL=c++_static \
  -DBUILD_SHARED_LIBS=OFF -DGGML_NATIVE=OFF -DGGML_OPENMP=OFF -DGGML_LLAMAFILE=OFF -DLLAMA_OPENSSL=OFF -DLLAMA_CURL=OFF
# arm64 for A73: also -DGGML_CPU_ARM_ARCH=armv8-a (no +dotprod, or it SIGILLs); for an A55 TV: armv8.2-a+dotprod
cmake --build build-tv --target llama-server -j
cp build-tv/bin/llama-server app/native/lib/armeabi-v7a/libwellybrain.so
```

The flags follow llama.cpp `docs/android.md` [documented]; the armv7 and static options are [inferred]. For an arm64
TV, the official `llama-*-bin-android-arm64.tar.gz` (77 MB, all tools, shared libraries) is a quick test [observed],
but a static `llama-server` alone is smaller.

`build.sh` change: one line after the dex is added, so zipalign still page-aligns the libraries.

```bash
[ -d app/native/lib ] && (cd app/native && zip -qr ../../$OUT/base.apk lib)   # lib/<abi>/libwellybrain.so
```

APK impact: a static `llama-server` is ~4–8 MB uncompressed and ~2–3 MB compressed, so the APK goes from 2.6 MB to
~5–6 MB, plus an extracted copy under `/data/app/…/lib` [estimate].

Launch command, from the `:brain` service (§4.4):

```java
File bin = new File(getApplicationInfo().nativeLibraryDir, "libwellybrain.so");
String key = new BigInteger(130, new SecureRandom()).toString(32);   // any app on the TV can reach 127.0.0.1
ProcessBuilder pb = new ProcessBuilder("/system/bin/sh", "-c",
    "echo 900 > /proc/self/oom_score_adj 2>/dev/null; exec /system/bin/toybox nice -n 10 \"$0\" \"$@\"",
    bin.getPath(), "-m", model.getPath(), "--host", "127.0.0.1", "--port", "8181", "--api-key", key,
    "-c", "1536", "-t", "2", "-tb", "3", "-np", "1", "--jinja", "--no-webui",
    "--cache-ram", "0",                                   // llama-server's host prompt cache defaults to GiBs
    "--chat-template-kwargs", "{\"enable_thinking\":false}");
pb.redirectErrorStream(true).redirectOutput(new File(getCacheDir(), "brain.log"));
```

Notes on these flags:

- `-t 2`: decode is bandwidth-bound and peaks at 2 threads on the Pi 5 [observed]. That leaves two cores for the UI
  thread, RenderThread and the system, so `:home` keeps 60 fps. `-tb 3` gives prefill one more core [inferred].
- Raising one's own `oom_score_adj` is allowed by the kernel. Whether SELinux lets `untrusted_app` write
  `/proc/self/oom_score_adj` is **unverified**: test it with the `cat` in §1.6. It is belt-and-braces; §4.4's unload
  policy is the real protection.
- Flags change between releases: `--cache-ram`/`-cram` replaced older cache flags in 2026 [observed]. Check
  `llama-server --help` for the pinned build.

In `WellyBrain.chain()`, the new brain is last, after Ollama:

```java
if (LocalBrain.ready()) out.add(new Brain("local:" + LocalBrain.model(), "http://127.0.0.1:8181/v1/chat/completions",
        LocalBrain.key(), LocalBrain.model(), 30000));
```

Then: `system(b)` returns the short local prompt (§4.3) when `b.name.startsWith("local:")`, `WellyTools.schema()` takes
a subset, and `/no_think` applies to `local:qwen3*`.

### 3.2 Option B′: llama.cpp through JNI

This means the `examples/llama.android` style: a small C++ bridge compiled with the NDK and loaded with
`System.loadLibrary`. It is in-process, so there are no exec, phantom-process or `oom_score_adj` questions, and it
lives in `:brain`. The cost is writing and maintaining a JNI layer: tokenizer, chat template, tool-call parsing and
grammar, all of which llama-server already provides behind the OpenAI API. **Not worth it for Welly** [inferred].

### 3.3 Option A: Google AI Edge LiteRT-LM (successor to the MediaPipe LLM Inference API)

| Item | Fact | Tag |
|---|---|---|
| Status | MediaPipe `tasks-genai` is deprecated / maintenance-only on Android; Google says migrate to LiteRT-LM | [documented] |
| Artifact | `com.google.ai.edge.litertlm:litertlm-android:0.18.0`; AAR declares **minSdk 24** (we're 26: fine) | [documented: developer.android.com skill page] |
| Contents | `classes.jar` + `jni/arm64-v8a/liblitertlm_jni.so` (21.5 MB in 0.16.1), 16 KB-aligned. **No armeabi-v7a** | [observed: LiteRT-LM #3512] |
| API | Kotlin: `Engine(EngineConfig(modelPath, Backend.CPU()))`, `initialize()` takes up to ~10 s; `createConversation(ConversationConfig(systemInstruction, tools…))`; `sendMessageAsync(contents, MessageCallback)` callback variant, which is usable from Java; tools as `@Tool` methods or `OpenApiTool` JSON; Apache-2.0 | [documented] |
| Zero-Gradle cost | Unzip the AAR. Add `classes.jar` to `javac -classpath` and to d8's inputs. Copy the `.so` into `lib/arm64-v8a/`. Add **kotlin-stdlib + kotlinx-coroutines** jars to d8 (the 0.17–0.18 AARs carry Kotlin 2.4 metadata). Hand-merge the AAR manifest (`<uses-native-library libOpenCL.so / libvndksupport.so required=false>` for GPU). Kotlin default arguments without `@JvmOverloads` mean passing every constructor argument from Java | [documented: LiteRT-LM #3753] / [inferred] |
| Model files | `.litertlm` only (Qwen3-0.6B 345–614 MB; Gemma 4 E2B 2,588 MB; Gemma 3 1B 584 MB, gated) | [observed: HF API] |
| GPU | OpenCL on Mali-G52 MC1: not worth it (§1.3) | [inferred] |
| APK impact | +~9–11 MB compressed (21.5 MB `.so` + Kotlin libraries) | [estimate] |

**Verdict:** a good engine on an **arm64** TV, but it buys nothing over llama-server for a 0.6B model, costs ~10 MB of
APK, and doesn't run on a 32-bit userland. Revisit only if the TV turns out to be arm64 *and* you want Gemma 4 E2B's
audio input, which is too slow here anyway.

### 3.4 Option C: MLC-LLM, ONNX Runtime GenAI

- **MLC-LLM:** arm64 only, built around an OpenCL GPU, heavy toolchain (TVM). **No** [observed: #2864].
- **ONNX Runtime GenAI:** Java AAR, builds for armeabi-v7a from source [documented]. But you'd build it yourself, it has
  no OpenAI server, and its small-model export zoo is thinner than GGUF's. **No.**

### 3.5 Thermal and the 60 fps rule

TV SoCs are passively cooled in a large chassis. 5–15 s bursts on 2–3 cores are unlikely to throttle [inferred]. The
real risk is **jank**, because inference shares four cores and one DRAM bus with `:home`'s rendering. Mitigations
[inferred]:

- 2 decode threads, nice +10.
- No inference while a focus animation runs. This doesn't really arise, since Welly waits on the answer anyway.
- Never during video, so the panel/MEMC bandwidth isn't contended.
- Measure with `dumpsys gfxinfo io.freemytv` while a request runs. **Accept only < 1 % janky frames.**

### 3.6 Required fix regardless: cleartext to the Mac and to 127.0.0.1

**Done:** `build.sh` writes this file into `out/res-gen` from `ollama_url` (host names and IPv4 only) and the manifest
references it. Still to verify on the TV: an Ollama request reaching the Mac.

`android:usesCleartextTraffic="false"` blocks `http://192.168.x.x:11434` (Ollama) and `http://127.0.0.1:8181`. Add
`app/res/xml/network_security_config.xml`, generated by `build.sh` from `ollama_url`'s host, and reference it from
`<application android:networkSecurityConfig="@xml/network_security_config">`:

```xml
<network-security-config>
  <base-config cleartextTrafficPermitted="false" />
  <domain-config cleartextTrafficPermitted="true">
    <domain includeSubdomains="false">127.0.0.1</domain>
    <domain includeSubdomains="false">192.168.1.10</domain>  <!-- from ollama_url -->
  </domain-config>
</network-security-config>
```

Matching IP literals in `<domain>` works in practice [unverified]; verify that an Ollama request reaches the Mac.

---

## 4. Design

### 4.1 Settings → "Welly offline brain" (download, verify, delete)

UI: one row in SettingsActivity (`:home`). States: *Not installed (~400 MB)* · *Downloading 37 % · 148/397 MB* ·
*Checking…* · *Installed · 397 MB · Delete* · *Not enough space (need 0.9 GB free)* · *This TV can't run it* (from the
probe: `isLowRamDevice()`, `MemoryInfo.totalMem < 2.5 GB`, or ABI without a bundled binary).

Model catalogue, pinned to a commit so the bytes can't change (hashes from the HF API, 2026-10-26):

| Id | URL (`https://huggingface.co/…`) | Bytes | SHA-256 |
|---|---|---|---|
| `qwen3-0.6b` (default) | `unsloth/Qwen3-0.6B-GGUF/resolve/50968a4468ef4233ed78cd7c3de230dd1d61a56b/Qwen3-0.6B-Q4_K_M.gguf` | 396,705,472 | `ac2d97712095a558e31573f62f466a3f9d93990898b0ec79d7c974c1780d524a` |
| `qwen3.5-0.8b` | `unsloth/Qwen3.5-0.8B-GGUF/resolve/6ab461498e2023f6e3c1baea90a8f0fe38ab64d0/Qwen3.5-0.8B-Q4_K_M.gguf` | 532,517,120 | `bd258782e35f7f458f8aced1adc053e6e92e89bc735ba3be89d38a06121dc517` |
| `qwen3-1.7b` (4 GB arm64 only) | `unsloth/Qwen3-1.7B-GGUF/resolve/d7f544eead698dbd1f15126ef60b45a1e1933222/Qwen3-1.7B-Q4_K_M.gguf` | 1,107,409,472 | `b139949c5bd74937ad8ed8c8cf3d9ffb1e99c866c823204dc42c0d91fa181897` |

What a `HEAD` on a `resolve` URL returns [observed, 2026-10-26]:

- `302` to a signed `us.aws.cdn.hf.co` URL that carries an `Expires=` parameter.
- `x-linked-size` = the byte count.
- `x-linked-etag` = the file's **SHA-256**.
- `accept-ranges: bytes`.

The CDN answered `Range: bytes=1000-1999` with **206** and `content-range: bytes 1000-1999/396705472`. The CDN's own
`etag` is *not* the SHA-256, so verify against the pinned hash.

Download algorithm (run in the main process as a `JobService` with `setRequiredNetworkType(NETWORK_TYPE_UNMETERED)` and
`setEstimatedNetworkBytes`, so `:home`'s heap and frame time are untouched; progress goes to a small state file that
Settings polls every 500 ms):

1. **Space check:** `new StatFs(getFilesDir().getPath()).getAvailableBytes() ≥ size − partBytes + 512 MB` (headroom
   for the KV cache, logs, and Google TV's own low-storage threshold). Otherwise show "need X GB".
2. **Resolve:** `HttpURLConnection` to the `resolve` URL with `setInstanceFollowRedirects(false)`. Read `Location`
   (relative or absolute) and check that `x-linked-size` matches the pinned size.
3. **Resume:** `GET Location` with `Range: bytes=<partBytes>-`.
   - `206` + matching `Content-Range` → append.
   - `200` → truncate and restart.
   - `403`/`410` (signed URL expired) → go back to step 2.
   - `416` → the part is complete, go to step 5.
4. **Stream:** a 64 KB buffer into `files/brain/<id>.gguf.part` via `RandomAccessFile`/`FileOutputStream(append)`.
   Persist progress every 1 MB. Timeouts: 15 s connect, 30 s read. Retry with backoff of 2, 4, 8 … 60 s.
5. **Verify:** stream the whole `.part` through `MessageDigest.getInstance("SHA-256")` on a background thread at
   `THREAD_PRIORITY_BACKGROUND`. This takes a few seconds with the ARMv8 SHA instructions via Conscrypt [estimate].
   Mismatch → delete and report. Match → `renameTo(<id>.gguf)`, which is atomic on the same filesystem.
6. **Delete button:** stop the brain (§4.4), delete `files/brain/*`, and show the bytes freed.

Keep the model in `getFilesDir()`, not external storage: it is private, survives app updates, needs no FUSE for
`mmap`, and is removed on uninstall [inferred]. The system `DownloadManager` would handle resume for free, but it only
writes to external storage (FUSE-backed on Android 11+), and moving the file into `/data/data` means a 400 MB copy. Use
it only if the hand-rolled code proves flaky [inferred].

### 4.2 Smart Brain Router: Tier 0 + three brains

```
words ──► Tier 0  WellyIntents (regex grammar, no model)   ── hit ──► WellyTools.run() ──► templated reply (≤ 100 ms)
            │ miss
            ▼
          Tier 1  Gemini 3.8 Flash → 3.5 Flash-Lite        (online && !benched)
          Tier 2  Ollama qwen3:8b on the Mac               (reachable in last 60 s)
          Tier 3  local llama-server, Qwen3-0.6B            (model installed && home in front && intent ∈ Tier-3 set)
            │ all fail
            ▼
          honest failure text (existing failed())
```

Routing rules [inferred]:

| Rule | Detail |
|---|---|
| Tier 0 first, always | Even online: lights, scene, launch_app, input, sleep_timer, music, settings. These are deterministic, instant and free, and they don't spend Gemini's ~20 requests/day on 3.8 Flash (spec §1.1). |
| Offline detection | `ConnectivityManager.getNetworkCapabilities(active)` lacks `NET_CAPABILITY_VALIDATED`, **or** the last Gemini call failed with `UnknownHostException`/connect timeout → mark "offline" for 60 s and skip Tier 1 without waiting 6 s. |
| 429 stickiness | Keep the existing 10-minute `BENCHED`. When the error body says the *daily* quota is exhausted, bench until **midnight Pacific** (RPD reset, spec §1.1). |
| Ollama reachability | `GET {ollama}/api/tags` with an 800 ms connect timeout, cached 60 s. A sleeping Mac then costs < 1 s instead of a 20 s read timeout. |
| Latency budget | Tier 1: 6 s (as today). Tier 2: 20 s. Tier 3: 30 s, with the status word "Thinking on the TV…" and the mood *Sleepy*. Abort Tier 3 if the first tool call hasn't arrived in 15 s. |
| Straight to Tier 3 | Never for open questions when online. Use Tier 3 only when Tiers 1–2 are unavailable **and** the utterance looks like control or chit-chat. When offline, `search_titles`, `whats_on`, `scores` and `weather` can't work anyway, so Tier 3 says so plainly. |
| Tools already acted | Keep today's rule: if a brain fails after a tool ran, reply "Done." and don't re-run on the next brain. |

Tier 0 grammar, a sketch for a new `WellyIntents.java`. It covers WellyTools' real arguments (rooms `parents | mine |
living | all`, fixtures `lamp | bulb`):

```java
static final Pattern LIGHTS = Pattern.compile(
  "^(?:hey\\s+)?(?:welly[,\\s]+)?(?:please\\s+)?(?:turn|switch|put)\\s+(on|off)\\s+(?:the\\s+|my\\s+)?"
+ "(?:(parents?'?|mom'?s?|dad'?s?|my|bedroom|living(?:\\s*room)?|lounge|all(?:\\s+the)?|every)\\s*)?(lamps?|bulbs?|lights?)"
+ "(?:\\s+in\\s+(?:the\\s+)?(parents?'?\\s*room|my\\s*room|living\\s*room|bedroom))?\\W*$", CASE_INSENSITIVE);
// also "<fixture> on|off" ("lamp off"), "lights out", "it's too dark" → scene bright,
// "movie mode|time" → scene movie, "bedtime|good night" → scene sleep,
// "open|launch|start|put on <app>" → launch_app (resolved against HomeApps names; else miss → LLM),
// "switch to hdmi <n>|live tv" → input, "sleep in <n> (min|minutes|hours)" | "cancel sleep timer" → sleep_timer,
// "pause|play|resume|next|skip|previous|go back" (whole utterance) → music.
```

- Reply templates follow the personality rules: one short sentence, no wit after a failure. Examples: "Lamp's on.",
  "Living room's off.", "Your bedroom lamp didn't answer — it may be on newer Kasa firmware."
- Anything ambiguous (two rooms, an unknown app name, negation like "don't…") is a **miss** and goes to the LLM chain.
- Write an utterance list (~80 phrasings from the spec's examples) as a unit check runnable on the Mac with plain
  `java`.

### 4.3 Prompt and tool budget for the small model

| Item | Cloud brains (unchanged) | Tier 3 local |
|---|---|---|
| System prompt | full personality (~400 tok) | ~120 tok: "You are Welly… one short sentence… call a tool for lights, scene, apps, inputs, sleep timer, music." |
| Tools | 16 | **6**: `lights`, `scene`, `launch_app`, `input`, `sleep_timer`, `music`. Arguments become `enum`s where possible (room, fixture, scene name, music action), so llama.cpp's grammar enforces them |
| Memory | 20 turns + facts | last **2** turns, no facts |
| Context | — | `-c 1536`; reject input > 300 tokens |
| Dynamic text | date/time inside the system prompt | **move "Now: …", the city and the name into the user message.** The static system + tools prefix is then byte-identical every turn, so llama-server's `cache_prompt` (default on, one slot `-np 1`) reuses its KV and only ~60 new tokens are prefilled [documented: llama-server prefix reuse] |
| Rounds | ≤ 4 tool rounds | **1**: for home tools, skip the second model round and use the Tier 0 reply template (saves ~5 s) |
| Thinking | — | off (`enable_thinking:false` / `/no_think`) |

Static prefix ≈ 120 + ~330 (6 compact schemas) ≈ **450 tokens**. Warm-up prefills it once: ~10–15 s at 30–45 tok/s
[estimate].

### 4.4 Warm-up, unload, and process placement

- **Process:** a new `BrainService` declared with `android:process=":brain"`, `exported=false`, and **never** started
  as a foreground service. `:home` binds it with `BIND_AUTO_CREATE` only while the Welly tab is open.
  - It inherits `:home`'s importance while home is in front, which avoids the cached-app freezer.
  - When the Welly tab closes it is unbound and drops to cached (≥ 900), so lmkd's medium-pressure kill (≥ 800) takes
    it long before `:home` (600 behind a video, 0 in front).
  - It owns the `llama-server` child. If `:brain` dies, the process group dies with it, and `:home` sees
    `onServiceDisconnected` and marks Tier 3 "not warm" [inferred].
- **Load:** when the Welly tab gains focus, if the model is installed, `MemAvailable ≥ model + 400 MB`, and
  `NowPlaying` reports no active playback:
  1. Start `llama-server`.
  2. Poll `GET /health` until `200`. The cold `mmap` from eMMC takes ~2–4 s at ~150–250 MB/s [estimate].
  3. Send one warm-up request with the static prefix and `max_tokens: 1` to fill the prompt cache.

  Tiers 1–2 answer meanwhile, and Tier 3 is offered only once it is warm.
- **Unload** (send SIGTERM to the child, then `stopSelf()` and exit `:brain`) on the first of:
  - 3 minutes idle on the Welly tab;
  - leaving the Welly tab, after 30 s;
  - `HomeActivity.onStop()`, meaning any app or video in front;
  - screen off / `ACTION_SCREEN_OFF` / the dream starting;
  - `onTrimMemory(≥ TRIM_MEMORY_RUNNING_LOW)` in `:home` or `:brain`.
- **Never** run while a video app is in front. That also keeps it clear of Android 12's phantom-process CPU kill.
- **Crash budget:** if the child dies twice in 10 minutes (OOM kill, SIGILL on a wrong ISA build), disable Tier 3 until
  the next app start and log `brain.log`'s tail to `AppLog`.

---

## 5. Recommendation

### 5.1 Decision

1. **Don't build the "Download offline brain" button yet.** On this hardware an on-TV LLM gives ~6–12 s answers for
   exactly the requests a parser handles in under 100 ms. When the internet is down, Google TV's voice recognizer
   usually is too [inferred], so an offline brain mostly serves *typed* or on-screen input. The real failure Tier 3
   covers is narrow: Gemini out of quota **and** the Mac asleep.
2. **Build now:** the **Tier 0 deterministic intent parser** plus the router (offline detection, Ollama reachability,
   RPD-aware bench), and the **cleartext fix**, without which Tier 2 can't work on the TV at all.
3. **Run the probe (§1.6)** and paste the output here.
4. **Then, if the TV qualifies,** add Tier 3 as **llama.cpp `llama-server` + Qwen3-0.6B Q4_K_M (~400 MB)**, built for
   whatever `abilist` reports. A TV qualifies with `MemAvailable ≥ 1 GB` with home open, `/data` ≥ 2 GB free, and
   `low_ram` false. Use LiteRT-LM only on an arm64 TV, and even then there's no speed reason to prefer it at 0.6B.

### 5.2 Phased plan

| Phase | Work | Effort | Gate |
|---|---|---|---|
| 0 | Device probe (§1.6); `network_security_config` generated by `build.sh` (127.0.0.1 + `ollama_url` host); confirm Ollama works from the TV | 0.5 day | — |
| 1 | `WellyIntents` (Tier 0) for 7 tools + ~80-utterance check; router refactor in `WellyBrain` (offline flag, Ollama `/api/tags` probe, midnight-PT bench, prompt with dynamic text moved to the user message) | 2 days | — |
| 2 | `tools/build-llama.sh` (NDK, static, ABI from probe); `build.sh` zips `app/native/lib`; manifest `extractNativeLibs`, `:brain` `BrainService`; spawn, `/health`, warm-up, unload policy; local `Brain` + 6-tool schema | 2–3 days | probe passes |
| 3 | Settings row + `JobService` downloader (Range resume, re-resolve on 403, SHA-256, StatFs, delete, progress) | 1.5 days | Phase 2 runs a hand-pushed model (`adb push` to `files/`) with < 15 s turns and < 1 % janky frames |
| 4 | On-TV eval: ~100 utterances, tool-call accuracy and latency for Qwen3-0.6B vs Qwen3.5-0.8B; pick the default | 1 day | — |
| 5 (optional) | Fine-tune **FunctionGemma 270M** on Welly's tools (Unsloth/Colab, Google's Mobile Actions recipe: 58 → 85 % on its task) and ship it as a 253–292 MB "fast" option at ~6–10 tok/s | 2–3 days | Phase 4 accuracy < 85 % |

Total ≈ **2.5 days without an on-TV model, 7–11 days with one** [estimate].

### 5.3 Risks

| Risk | Likelihood | Impact | Mitigation |
|---|---|---|---|
| 32-bit userland | High | Rules out LiteRT-LM/MLC; armv7 llama.cpp ~20–40 % slower | Build armv7 llama.cpp; the probe decides |
| Too little free RAM (3 GB set) | Medium | lmkd kills apps, or `:home` stutters | Gate on `MemAvailable`; 0.6B only; `:brain` cached when idle; unload on `onStop` |
| Launcher killed | Low with the design | Severe (blank TV) | Never a foreground service in `:brain`; model only while home is in front; `oom_score_adj` 900 attempt |
| UI jank during inference | Medium | Breaks the 60 fps rule | 2 threads, nice +10; measure with `gfxinfo` |
| Phantom process killer / freezer | Low (only runs in front) | Child killed or frozen | Unload on `onStop`; restart on next Welly open |
| Wrong arguments from a 0.6B model | Medium–High | Wrong room lit | Enum arguments + grammar; Tier 0 handles the common phrasings first |
| ~10 s answers feel broken | High | Users stop using Welly offline | Status word "Thinking on the TV…"; skip the 2nd round; Tier 0 first |
| Model licence | Low | — | Qwen = Apache-2.0; Gemma = Gemma terms (FunctionGemma); avoid MobileLLM-R1 (non-commercial) |
| Executable-download policy | n/a when sideloaded | — | Ship the binary in the APK; download weights only |
| Storage | Low on 64 GB eMMC, medium on 16 GB | — | StatFs + 512 MB headroom; delete button |

### 5.4 The honest alternative: no model at all

For Welly's TV-side job, a deterministic intent parser plus the two remote brains covers ~90 % of real requests
[inferred]. A parser:

- covers lights, scenes, apps, inputs, sleep timer, transport and settings with **0 MB, 0 ms** and no risk to `:home`;
- needs the internet only for what genuinely needs it (titles, listings, scores, weather);
- needs the Mac only for open conversation without Gemini quota.

An on-TV model adds fuzzy phrasing ("it's too bright in here") and offline chit-chat, at ~400 MB and 6–12 s a turn.
Ship Tier 0 now. The 400 MB model is a Phase 2+ experiment, gated on the probe.

---

## Sources

**TV hardware and platform**
- TCL chipset/platform table (TCL Discussion Telegram docs): https://faserf.github.io/TCL-Discussion-Telegram/chipsets/
- FlatpanelsHD, TCL C8K (QM8K) review: https://www.flatpanelshd.com/review.php?subaction=showfull&id=1750665779
- FlatpanelsHD, TCL C855 review: https://www.flatpanelshd.com/review.php?subaction=showfull&id=1729508352
- Wikipedia, List of MediaTek SoCs (Pentonic table): https://en.wikipedia.org/wiki/List_of_MediaTek_systems_on_chips
- MediaTek Pentonic 700 product page / press release: https://www.mediatek.com/products/pentonic/700 · https://www.mediatek.com/press-room/mediatek-launches-pentonic-700-chipset-for-premium-120hz-4k-smart-tvs
- Tab-TV, Pentonic processors: https://www.tab-tv.com/tv-8k-processor-mediatek-pentonic-2000/
- AVS Forum, 2023 QM8 owners thread: https://www.avsforum.com/threads/tcl-2023-qm8-mini-led-google-tv-owners-thread.3275454/page-156
- AVS Forum, 2026 QM8L/QM7L thread: https://www.avsforum.com/threads/2026-tcl-qm8l-c8l-and-qm7l-c7l-discussion-thread.3341179/
- Costco 85" QM6K Pro: https://www.costco.com/p/-/tcl-85-class-qm6k-pro-series-4k-uhd-qd-mini-led-smart-tv-allstate-3-year-protection-plan-bundle-included-for-5-years-of-total-coverage/4000364009 · 85" Q77K: https://www.costco.com/p/-/tcl-85-class-q77k-series-4k-uhd-qled-smart-tv-allstate-3-year-protection-plan-bundle-included-for-5-years-of-total-coverage/4000364057
- RTINGS QM7K / QM6K: https://www.rtings.com/tv/reviews/tcl/qm7k · https://www.rtings.com/tv/reviews/tcl/qm6k
- Android Developers Blog, 64-bit app compatibility for Google TV and Android TV (Aug 2025): https://android-developers.googleblog.com/2025/08/64-bit-app-compatibility-for-google-tv-android-tv.html
- FlatpanelsHD on the 64-bit transition: https://www.flatpanelshd.com/news.php?subaction=showfull&id=1755842190
- AFTVnews on the 64-bit requirement: https://www.aftvnews.com/google-sets-new-64-bit-app-requirements-for-google-tv-and-android-tv/
- Kodi forum, "ARM64 V8a on TCL TV": https://forum.kodi.tv/showthread.php?tid=374906
- 32-bit-only Google TV install failures: https://github.com/umbrella22/Vesper/issues/3 · https://github.com/gladimdim/super-desktop/issues/7
- Android Police / Android Authority on TV RAM minimums: https://www.androidpolice.com/google-tv-higher-android-tv-lower-ram-requirment/ · https://www.androidauthority.com/android-tv-minimum-ram-3497217/

**Android memory, processes, exec**
- AOSP lmkd: https://source.android.com/docs/core/perf/lmkd
- AOSP `ProcessList.java` (oom_adj constants): https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/services/core/java/com/android/server/am/ProcessList.java
- Android TV memory guide: https://developer.android.com/training/tv/playback/memory
- Android 10 behaviour changes (W^X / execve): https://developer.android.com/about/versions/10/behavior-changes-10
- Executing binaries from nativeLibraryDir: https://chaosgoo.com/en/android-run-executable-binary-bypass/ · https://github.com/agnostic-apollo/Android-Docs/blob/master/site/pages/en/projects/docs/apps/processes/app-data-file-execute-restrictions.md
- Phantom process killer: https://github.com/termux/termux-app/issues/2366 · https://issuetracker.google.com/issues/205156966

**Engines**
- LiteRT-LM repo: https://github.com/google-ai-edge/LiteRT-LM · Kotlin API: https://github.com/google-ai-edge/LiteRT-LM/blob/main/docs/api/kotlin/getting_started.md
- LiteRT-LM on Android: https://ai.google.dev/edge/litert-lm/android · https://developer.android.com/agents/skills/device-ai/litert/litert-lm/skill
- LiteRT-LM issues (Kotlin 2.4 metadata #3753, AAR contents #3512): https://github.com/google-ai-edge/LiteRT-LM/issues/3753 · https://github.com/google-ai-edge/LiteRT-LM/issues/3512
- LiteRT 32-bit request: https://github.com/google-ai-edge/LiteRT/issues/3926
- MediaPipe LLM Inference (deprecated): https://ai.google.dev/edge/mediapipe/solutions/genai/llm_inference/android
- LiteRT-LM blog: https://developers.googleblog.com/blazing-fast-on-device-genai-with-litert-lm/
- llama.cpp Android docs: https://github.com/ggml-org/llama.cpp/blob/master/docs/android.md · armv7 fix PR #5702: https://github.com/ggml-org/llama.cpp/pull/5702
- llama.cpp releases (android-arm64 prebuilt): https://github.com/ggml-org/llama.cpp/releases
- llama.cpp Arm performance discussion (Pi 5 numbers): https://github.com/ggml-org/llama.cpp/discussions/30069
- llama.cpp Android i8mm/NEON regression: https://github.com/ggml-org/llama.cpp/issues/10662
- llamafile #980 (dotprod-gated repack): https://github.com/mozilla-ai/llamafile/issues/980
- BitNet #411 (A73 = ARMv8.0, no dotprod): https://github.com/microsoft/BitNet/issues/411
- llama-server KV/prefix reuse: https://github.com/ggml-org/llama.cpp/discussions/13606
- Qwen3.5 tool calls in think blocks: https://github.com/ggml-org/llama.cpp/issues/20837
- Mali GPU slower than CPU: https://github.com/ggml-org/llama.cpp/issues/5965 · https://github.com/ggml-org/llama.cpp/discussions/9464
- MLC-LLM armeabi-v7a: https://github.com/mlc-ai/mlc-llm/issues/2864
- ONNX Runtime GenAI build: https://onnxruntime.ai/docs/genai/howto/build-from-source.html

**Models and benchmarks**
- Gemma 4 overview: https://ai.google.dev/gemma/docs/core · blog: https://blog.google/innovation-and-ai/technology/developers-tools/gemma-4/
- Gemma 4 E2B LiteRT-LM card (benchmarks incl. Pi 5): https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm
- FunctionGemma: https://huggingface.co/google/functiongemma-270m-it · https://blog.google/innovation-and-ai/technology/developers-tools/functiongemma/ · https://ai.google.dev/gemma/docs/mobile-actions
- Qwen3 technical report (BFCL v3 table): https://arxiv.org/abs/2505.09388
- Qwen3.5 small models: https://huggingface.co/Qwen/Qwen3.5-0.8B · https://huggingface.co/Qwen/Qwen3.5-2B · https://unsloth.ai/docs/models/qwen3.5
- GGUF files (sizes/hashes via the HF API): https://huggingface.co/unsloth/Qwen3-0.6B-GGUF · https://huggingface.co/unsloth/Qwen3-1.7B-GGUF · https://huggingface.co/unsloth/Qwen3.5-0.8B-GGUF · https://huggingface.co/bartowski/google_gemma-3-1b-it-GGUF · https://huggingface.co/bartowski/Llama-3.2-1B-Instruct-GGUF · https://huggingface.co/bartowski/SmolLM2-1.7B-Instruct-GGUF · https://huggingface.co/bartowski/SmolLM2-360M-Instruct-GGUF · https://huggingface.co/unsloth/gemma-3n-E2B-it-GGUF · https://huggingface.co/unsloth/gemma-4-E2B-it-GGUF · https://huggingface.co/unsloth/functiongemma-270m-it-GGUF
- LiteRT model files: https://huggingface.co/litert-community/Gemma3-1B-IT · https://huggingface.co/litert-community/Qwen3-0.6B · https://huggingface.co/google/gemma-3n-E2B-it-litert-lm
- MobileLLM-R1: https://huggingface.co/facebook/MobileLLM-R1-950M · https://arxiv.org/abs/2509.24945
- SBC LLM evaluation (Pi 4/Pi 5 bands): https://arxiv.org/html/2511.07425v1
- Stratosphere Lab, LLMs on Pi 5: https://www.stratosphereips.org/blog/2025/6/5/how-well-do-llms-perform-on-a-raspberry-pi-5
- BFCL leaderboard: https://gorilla.cs.berkeley.edu/leaderboard.html
- Quantization vs tool calling (Qwen3-0.6B / Llama-3.2-1B): https://dev.to/happynood/does-quantization-break-tool-calling-i-measured-it-on-a-4gb-laptop-gpu-bfcl-3-seeds-bootstrap-185l
