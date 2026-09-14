# 第三方资源与许可

本项目以 [MIT License](LICENSE) 发布。但它包含下列第三方资源，这些资源各自的许可条款独立于本项目，
再分发时**必须一并保留**其版权声明与许可原文。

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

---

## Project Gutenberg（仅测试固件）

`app/src/testDebug/resources/readium/public/` 下的两个 EPUB 文件取自 Project Gutenberg 的公版书，
仅用于离线兼容性测试，**不随发布产物分发**。

每本书的作品信息、下载地址与 SHA-256 校验值见同目录的 `SOURCE.md`。这些出版物**内嵌的
Project Gutenberg 许可与商标条款继续适用**，不因本项目采用 MIT 而改变。
