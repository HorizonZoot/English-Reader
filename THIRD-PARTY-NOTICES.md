# 第三方资源与许可

本项目自有源码以 [MIT License](LICENSE) 发布。包含离线语音运行库的应用二进制受
espeak-ng 的 GPLv3 条款约束；再分发须提供对应源码与构建材料，并保留以下版权及许可。

## 离线语音

| 组件 | 来源 | 许可 |
| --- | --- | --- |
| sherpa-onnx 1.13.8 Android AAR | https://github.com/k2-fsa/sherpa-onnx/tree/v1.13.8 | Apache-2.0 |
| ONNX Runtime（由上述 AAR 提供） | https://github.com/microsoft/onnxruntime | MIT |
| espeak-ng（静态链接于上述 AAR） | https://github.com/espeak-ng/espeak-ng | GPL-3.0 |
| Apache Commons Compress 1.28.0 | https://commons.apache.org/proper/commons-compress/ | Apache-2.0 |
| LibriTTS-R / Piper libritts_r medium INT8 | https://huggingface.co/rhasspy/piper-voices/tree/main/en/en_US/libritts_r/medium | 数据集 CC BY 4.0，模型卡随资产保留 |
| Kokoro v0.19 INT8（用户显式下载） | https://github.com/k2-fsa/sherpa-onnx/releases/tag/tts-models | Apache-2.0，原 LICENSE 随下载包保留 |

运行库许可原文位于 `app/src/main/assets/licenses/`，随 APK 一起分发。官方 AAR 的 SHA-256
为 `633c24321e06b1fe79feafa03ea16cbc0f8a286641e2da3559bac91bdb13bd96`；下载模型的
固定来源、版本和校验值见 `TtsModelCatalog.kt`。本项目只打包并解包模型，不修改权重。

LibriTTS-R 来源：https://www.openslr.org/141/ ，由 Yuma Koizumi 等发布，基于 LibriTTS，
使用 Miipher 改善音质。Piper 模型由 English lessac medium 在 train-clean-360 上微调。
数据集许可：https://creativecommons.org/licenses/by/4.0/ 。Jen、Ashley、Juliana 来自该数据集
公开的录音者名称，分别对应 Jen Kidd、Ashley Candland、Juliana M.；名称不表示其为应用背书。
Kokoro 音色名称使用 v0.19 官方映射，不能与 v1.x 的音色编号混用。

---

## ECDICT（离线词典数据）

`app/src/main/assets/dict_base.tsv` 中的词条由 ECDICT 提取整理，共 7,005 条。

- 项目地址：https://github.com/skywind3000/ECDICT
- 许可：MIT

```
MIT License

Copyright (c) 2025 Linwei

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```
