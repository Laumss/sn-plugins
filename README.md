# Notipal

很多人拿起 Supernote，图的就是一块安静的纸面，能踏实读书、安心写字。大多数时候，自带的笔记和文档应用确实守住了这份安静，直到你为了换一支笔点开三层菜单，或者为了对照另一份 PDF 不得不退回文件列表，刚理顺的念头就这么断在了半路上。

Notipal 是一款为 **Supernote 墨水屏设备**开发的工具箱插件，想做的事很单纯：抹平这些日常读写里的小磕绊，又不把你的本子变成一台喧闹的电脑。它会在笔记和文档界面加上一条小巧的悬浮工具栏，里面放着 8 个常驻剪贴板、一键换笔条、文档裁剪与长图拼接、图片插入、基于 LocalSend 的局域网传输，以及文档直接分享。

它和 **Mosaic** 是同一位作者并行开发的，后者是一款 Supernote 手写白板插件。因为两款插件从一开始就一起打磨，Notipal 里几乎所有工具都能直接在 Mosaic 白板上用。Mosaic 本身不开源，随 Notipal 同步发布，它的完整说明也写在这份文档的后半部分，详见 [Mosaic](#mosaic)。

贯穿整个项目的一条底线是：**所有流转都只发生在你的局域网里。** Notipal 没有自己的云服务，不要账号，也不在你的笔记和其他设备之间插任何中间人。

## 核心亮点

- **在原生笔记与文档里直接[快速切换](#快速切换)。** 装好 Mosaic 后，在任意笔记或文档页面底部单指上滑，就能拉出最近打开的笔记、文档和白板列表，点一下直接跳转。它在 Supernote 原生应用里直接生效，不需要先打开 Mosaic。
- **随手就能唤出的草稿纸 [Sketch](#sketch)。** 快速切换器里藏着一块手写区，用起来像桌角贴着的一张便利贴。不管你正在读哪本书、写哪页笔记，上滑就能记下电话号码或一闪而过的念头，点一下空白处就能回到刚才的页面。你也可以把白板里的某张卡片放进来，方便在其他文件里随时抬头对照。
- **为深度阅读准备的[文档截图](#文档截图)。** 裁剪当前页的图表，把连续几页拼成长图或网格图，或者用[智能套索](#智能套索)直接框选屏幕上任意一块区域。切好的图可以排队等着插进笔记、发给手机，或者直接变成 Mosaic 白板上的图片卡片。
- **看懂你圈了什么的[智能套索](#智能套索)。** 圈住手写字，它先识别成纯文本再发出；选中文框或文档文字，直接按原文本发送；选中的是指向其他笔记或文档的链接，它会把对应的原文件发过去。全程走 LocalSend。
- **[笔槽](#笔槽)。** 一条贴在手边的竖向笔条，一键切换常用笔、橡皮擦和套索，还补上了比系统自带更粗的笔尖与更宽的橡皮。
- **顺着墨水屏节奏设计的 [AI 中继](#ai-中继)。** 对着一段手写笔记、一张截图或手写数学公式发问，回答通过手机上的 RikkaHub，或者扫码打开的浏览器网页实时流式送回屏幕上。
- **[边读边写（Nomad 与 Manta 专属）](#边读边写nomad-与-manta)。** 把半透明的 Mosaic 白板直接浮在文档上方，在可无限向下滚动的笔记卡片里做摘录，用机身侧滑条就能给底下的文档翻页，全程不用关掉白板，手感接近 reMarkable。
- **与 [Mosaic](#白板上的-notipal) 真正打通。** 笔记、文档、白板和 Sketch 共用同一套 8 格剪贴板；笔记和 PDF 页面可以直接导入成卡片；截图、收到的文本和 AI 回答都能直接落到白板上。

## 快速上手

### 获取插件

- **InkHub**：在 Supernote 内置的 InkHub 里搜索 Notipal 和 Mosaic，直接安装。
- **GitHub**：前往 [GitHub Releases](https://github.com/Not-Just-Supernote/sn-plugin-notipal/releases) 下载最新版的 Notipal 和 Mosaic 安装包（`.snplg`），拷贝到设备的 `/sdcard/MyStyle/` 目录下，按常规插件方式安装即可。
- **自行编译**：如果想自己动手构建，请参见 [从源码构建](#build-from-source)。

### 使用前提

| 你想用的功能 | 需要的条件 |
| --- | --- |
| 悬浮工具栏、剪贴板、笔槽、截图 | 一台支持插件的 Supernote |
| 跨设备收发文件与文字 | 同一 Wi-Fi，以及另一台设备上装好 [LocalSend](https://localsend.org) |
| 导出带手写批注的 PDF | 安装 DocuCraft 插件 |
| AI 中继 | 同一 Wi-Fi 下装有 RikkaHub 的安卓手机，或任意浏览器配合模型服务商的 API Key |
| 白板、快速切换与 Sketch | 安装 Mosaic 插件 |

### 首次启动

Notipal 在系统里有三个入口：

- **笔记工具栏**：点击 Notipal（Inkling）图标，打开或关闭笔记里的悬浮工具栏。
- **文档工具栏**：同一个图标，控制文档界面的悬浮工具栏。
- **套索菜单**：在笔记或文档里圈选内容后，点击菜单里的**剪贴板（Clipboard）**即可直接存进槽位。

第一次打开时，Notipal 会申请文件读写、删除权限以及网络访问权限。每一项都请选择**始终允许（Always allow）**。Notipal 需要在后台保持待命，如果只给单次授权，后台服务过一会儿就会静默失效。

## 悬浮工具栏

工具栏由一列主工具和旁边一条窄副栏组成，副栏放着剪贴板槽位和几个快捷按钮。用手指按住可以把它拖到屏幕任意位置避开手掌；用笔直接点按钮只会触发功能，不会把墨水漏画到底下的纸面上。

工具栏会跟着你所在的界面自动切换按钮：

| 当前界面 | 主工具列 | 副栏 |
| --- | --- | --- |
| 笔记 | 剪贴板管理、插入图片、智能套索、笔槽、AI 中继\*、插入链接\* | 剪贴板槽位、文本接收、笔记转 Mosaic、收起 |
| 文档 | 剪贴板管理、文档截图、智能套索、笔槽、AI 中继\* | 剪贴板槽位、分享文档、文档转 Mosaic、收起 |
| Mosaic | 剪贴板管理、插入图片、智能套索、笔槽、AI 中继\* | 剪贴板槽位、文本接收、墨水调色板、收起 |

\* 标注星号的是可选工具。AI 中继和插入链接默认关闭，需要时在设置里打开。

插入图片和文档截图共用同一个位置：看文档时它是截图按钮，回到笔记或白板上就变成插图按钮。

点**收起（Collapse）**，工具栏会贴到最近的屏幕边缘变成一个小把手；在页面上双指双击也能立刻收起。轻点把手，或者顺着屏幕内侧滑一下，工具栏就会重新展开；长按把手则会彻底关闭 Notipal。

工具栏也很懂得自己让路：当 AI 窗口或 Lexi 小窗打开时，它会自动收起成把手；当 Mosaic 菜单、快速切换器或 Lexi 全屏窗口弹出时，它会彻底隐藏起来。

## 功能详解

### 剪贴板

整理笔记时，系统自带的那一格剪贴板实在太脆弱，一不小心就被新复制的东西顶掉了。Notipal 在副栏留了 8 个持久槽位，有内容的槽位会反色显示为黑底白字，一眼就能看清哪些格子装着东西。如果屏幕高度放不下全部 8 格，直接在数字列上上下滑动就能翻看。

**存入。** 在笔记或文档里用套索圈选内容，点击套索菜单里的**剪贴板**并选定槽位即可。手写笔迹、图形、文本框、图片和链接都能完整保存（唯一例外是包含标题的选区，受系统限制无法保存）。在 Mosaic 白板上更直接：圈住笔迹后点一下槽位编号就能存进去。覆盖已有槽位前会先询问；如果长按一个空槽位，则会执行剪切（保存笔迹后将原笔迹从画布上移走）。

**粘贴。** 在没有选中任何内容时，点击一个已占用的槽位。在笔记里，内容会回到当初复制时的原坐标；如果那个位置已经写了别的东西，它会自动挪到附近空位，并保持套索选中状态，方便你伸手就拖到顺眼的地方。

| 粘贴到 | 会落入什么内容 |
| --- | --- |
| 笔记 | 槽位里保存的全部元素 |
| 文档 | 仅支持手写笔迹与图形；包含文本框、图片或链接的槽位会被拒绝 |
| Mosaic | 手写笔迹落到视野中央；文本框自动转为文本卡片，图片转为图片卡片；含链接的槽位会被拒绝 |

点开**剪贴板管理（Clipboard Manager）**可以一屏预览全部 8 个槽位：轻点缩略图直接粘贴（在 Mosaic 里用于存入），长按可以全屏看清细节。左下角的**清空剪贴板（Clear clipboard）**会在确认后清空所有槽位；如果只想清掉某一格，直接长按工具栏上对应的数字就行。

这 8 个槽位在笔记、文档、Mosaic 和 Sketch 之间完全互通，你还可以把它们整套同步到另一台 Supernote 上，详见[剪贴板同步](#剪贴板同步)。

### 笔槽

笔槽借鉴了 Kindle Scribe 的交互思路，在页面旁边放上一条竖向笔条，不用每次换笔都点开系统菜单打断书写节奏。

点击**笔槽（Pen Slots）**展开笔条。最上面一格用来折叠，最底下的齿轮打开**笔槽设置（Pen Slot Settings）**，中间最多排 9 支常用笔，每格角落都印着笔尖粗细。

- **点击任意一格**：立刻把当前笔记或文档的笔切换到该预设。正在使用的格子会反色高亮，哪怕你从 Supernote 自带菜单里换了别的笔，它也会跟着同步状态。
- **再点一次当前激活的格子**：直接切回你刚才用过的上一支工具，在写字和擦除、圈选之间来回切非常顺手。
- **折叠**：把整条笔栏缩成一个小方块，再点一下展开。双指双击页面同样能折叠，手指按住笔条可以拖到任意位置。

点齿轮（或长按笔槽按钮）进入**笔槽设置**，9 个槽位都可以自由配成针管笔、钢笔、马克笔、荧光笔、橡皮或套索，自选灰度（黑、深灰、浅灰、白）与粗细。除了系统原有规格，笔槽还解锁了更宽的笔尖与橡皮尺寸：

| 工具 | 系统自带最粗 | 笔槽可选范围 |
| --- | --- | --- |
| 针管笔 | 2.0 | 最高 3.0 |
| 钢笔 | 2.0 | 最高 5.0 |
| 书法笔（蘸水笔） | 2.0 | 3.0, 4.0, 5.0, 6.0, 7.0, 7.5 |
| 荧光笔（马克笔） | 单一宽度 | 4.0, 5.0, 6.0 |
| 橡皮擦 | 2.5 | 0.7, 1.9, 3.3, 5.0 |

有两个小细节需要留意：笔槽只改变你接下来落笔用的工具（纸上已经写好的笔迹不会跟着变）；另外，Supernote 原生侧边栏里的笔刷图标不会随着笔槽的切换而同步变化。

在 Mosaic 白板上，这条笔栏切换的是白板画笔，对应的 9 个槽位来自墨水调色板（与笔记、文档的笔槽分开存储）。如果想修改白板上已经写好的笔迹样式，请使用[墨水调色板](#墨水调色板mosaic)。

### 智能套索

智能套索会看你手上有没有选中东西，自动切换两种工作方式，图标也会跟着变化。

**已有选区时，通过 LocalSend 向外分享。** Notipal 会识别你选中的内容并打开发送面板：

| 你选中的内容 | 实际发出的内容 |
| --- | --- |
| 手写笔迹 | 经 Supernote 手写识别引擎转换后的纯文本 |
| 文本框 | 其中的文字内容 |
| 指向笔记或文档的链接 | 被链接的那份原文件本身 |
| 网页链接 | 网址 URL |
| 文档里的文字（文本选择或高亮） | 原始文本 |
| Mosaic 上的笔迹或文本卡片 | 识别后的文本，或卡片里的文字 |

识别出的手写内容和文本框会严格保留它们在页面上的阅读顺序。如果你在 LocalSend 里设好了默认设备，单段文字会直接发过去，省去多点一次确认。

**没有选区时，截取屏幕任意区域。** 直接用笔在屏幕上随手画一圈，周围会自动贴出一个选框。拖动四边、四角或中间手柄调好范围，再决定去向：

- **发送到其他设备**：作为图片通过 LocalSend 发出。
- **加入文档截图**：存进[插入图片](#插入图片)的截图列表中，随时插入笔记。
- **生成 Mosaic 卡片**：直接做成图片卡片放上 Mosaic 白板，并立刻跳转过去。

不管是笔记、文档还是白板界面，这个框选截图都能用。如果中途分神把选框晾在屏幕上，30 秒后它会自动收起。

长按智能套索按钮是个快捷入口，可以直接把 8 格剪贴板同步给另一台设备，见[剪贴板同步](#剪贴板同步)。

### 文档截图

认真啃一本书或一篇论文时，顺手把图表、引文和表格剪下来，回头统一整理进笔记是很自然的习惯。Notipal 提供了三种截图方式，最后都会汇集到同一个地方，随时插进笔记、发给手机或放上白板：

- **单页截图**：截取当前文档页面并自由裁剪。
- **长截图**：把连续几页拼成一张长竖图或网格图。
- **区域截图**：用[智能套索](#智能套索)在文档、笔记或白板上随时框选一块区域。

读文档时，你可以一口气把要摘录的图全都切进队列，不用反复切换应用。等回到笔记本，每点一次插入图片，队列里的下一张图就会按顺序落到纸上。

**在文档里点「文档截图（Doc Screenshot）」**定格当前页并进入裁剪界面，调好选框后有四个动作可选：

| 操作 | 效果 |
| --- | --- |
| 下一张插入（Insert Next） | 加入待插队列；回到笔记里每点一次插入图片，就依次放进一张 |
| 加入截图列表（Add to Queue） | 存入插入图片的截图列表中（保留最近 20 张） |
| 发送到其他设备（Send to Other Devices） | 直接通过 LocalSend 传出 |
| 拼接（Stitch） | 开启一次长图拼接，或把当前页接在正在拼的长图后面 |

如果同一页上有好几块想单独裁下来的内容，打开 **Multi** 开关就行，裁完一张不会退出编辑器，可以接着裁下一块。

**长图拼接**能把跨页内容接成一整张图。截完这一页，翻到下一页再截一次；凑齐两页后，拼接编辑器就会自动打开：

- **长图（Long）**：将多页竖向接成一条。直接拖动接缝处微调重叠区域，或者点**自动对齐（Auto Align）**让 Notipal 自己算好拼缝。
- **网格（Grid）**：把多页按列排成网格，支持按行排序或按列排序。
- 通过**加页（Add Page）**、**移除末页（Remove Last）**和**倒序（Reverse）**随时调整页面顺序。

没拼完的长图会在后台暂存 15 分钟，中途离开一会儿也不怕白忙。

**回到笔记里，同一个按钮变成「插入图片（Insert Image）」。** 如果待插队列里有图，轻点一下直接落到页面上；队列为空时则打开选图器。无论队列里有没有图，长按按钮随时都能直接进选图器。

### 插入图片

插入图片会打开一个全屏图库，左侧栏按来源分门别类：

- **最近（Recent）**：汇总下方各处最新的图片，也包含 Supernote 系统自带的截屏。
- **截图（Screenshots）**：你切好的文档截图，排在待插队列里的会置顶显示。
- **来自 LocalSend（From LocalSend）**：其他设备发过来的图片。
- **常用目录**：Document、EXPORT、MyStyle、Note、SCREENSHOT 和 INBOX。
- **回收站（Trash）**：位于最底部。

轻点图片就能多选，支持跨目录混选，缩略图上的编号标明了它们插入时的先后顺序。选好后可以：

- **插入（箭头图标）**：把选中的图片一张接一张放进笔记，每张插入后都处于选中状态，方便立刻拖动排版。
- **裁剪并插入（单张图片时）**：微调画幅，并可套用滤镜（**原图**、**增强**或**黑白文档**），墨水浓度还能在 100% 到 20% 之间按需调节。
- **删除（垃圾桶图标）**：把图片移入回收站，30 天内随时可以找回，超期后彻底清除。

来自 LocalSend 或待插队列的图片一旦插入成功就会自动清理掉，免得目录越积越乱。在 Mosaic 白板上，插入的图片会变成图片卡片，在空白处并排摆好。

### 文本接收

遇到大段引文、网址或参考文献，在电脑或手机上打字粘贴，总比在屏幕上硬抄省事得多。文本接收能让你把另一台设备上的文字直接送到 Supernote 的页面上。

1. 点击副栏的**文本接收（Text Receive）**，页面上会出现一个小气泡。
2. 把气泡拖到你希望文字落脚的位置。
3. 在手机或电脑上用 LocalSend 发送文字。

收到的每条消息都会在气泡所在位置生成一个文本框。紧接着发来的多条文字会自动往下顺排，并绕开页面上已经写好的字。点一下气泡可以放下仍在排队的文字，长按则丢弃暂存内容。

它在笔记和 Mosaic（在白板上会变成文本卡片）里都能用。发来的文字具体去往哪里，取决于你当前打开了什么界面：

| 屏幕当前状态 | 收到的文字去往哪里 |
| --- | --- |
| 停留在笔记或白板，画面无遮挡，且已开启文本接收 | 直接送入文本接收气泡 |
| 同上，但未开启文本接收 | 存入系统剪贴板，同时弹窗询问是否开启文本接收 |
| 停留在文档界面、笔记之外的任意处，或画面上有浮层遮挡（系统菜单或弹窗、其他插件窗口、Notipal 面板、展开的 AI 窗口、Mosaic 菜单、快速切换器或 Lexi） | 存入系统剪贴板 |

因为兜底方案会写进系统剪贴板，就算你正在某个搜索框或输入框里打字，也能直接从键盘剪贴板里把刚发过来的文字粘进去。

插入文本框的字体和字号可以在设置的**插入文本框（Inserted text boxes）**里调整。Notipal 会自动读取 `/sdcard/Document/Fonts/` 目录下的 `.ttf` 或 `.otf` 自定义字体。

### 插入链接

这是笔记里的一个可选工具，在设置的**可选工具（Optional Tools）**中启用。点开后会调出文件浏览器，选中任意文档即可在笔记里插入一条风格与原生完全一致的链接；开启多选模式还能一次插好几条。

### 墨水调色板（Mosaic）

在 Mosaic 白板上，副栏里的墨水调色板专门用来修改已经写好的笔迹样式。圈选笔迹后点击它，打开完整面板重设笔型、灰度和粗细。如果没选中任何内容就点它，屏幕上会浮出一个装着 9 组预设的小气泡，之后圈完笔迹直接点一下预设就能一步换装。所有样式改动都会记进 Mosaic 的撤销历史里。

在笔记和文档里，这个位置留给了笔槽。

## AI 中继

把一个完整的 AI 聊天客户端硬塞进墨水屏平板，用起来通常很难受：处理器跟不上流式输出的速度，屏幕一顿一顿地闪，最后人全耗在等本子刷新上。AI 中继换了个思路，把 Supernote 留作专注的读写界面，把联网调用和重活交给手边算力更强的设备。

- Supernote 只负责送出问题、把答复排好版画在纸上。真正向大模型发请求的是同一局域网里的手机或浏览器页面，中间不经过任何 Notipal 服务器。
- 使用期间连接保持长开，模型刚吐出头几个字，墨水屏上就能同步流出来。
- 提问素材直接从纸面上取：识别好的手写字、框选的图表、手写数学公式都能直接塞进输入框，不用照着重新敲一遍。
- 你停下来看书或写字时，连接会自动休眠省电，再次提问时瞬间唤醒；休眠期间后台已经跑完的回复也不会丢失。

**为什么做成现在这样。** 这套方案是在半年时间里，把三种架构并排做出来反复测速跑出来的：

1. **Supernote 直接调模型 API。** 写起来最省事，用起来也最慢。每一次 TLS 握手和每一包流式数据都要硬挤过墨水屏自己的网络栈和处理器。
2. **手机中转流式结果。** 把 API 请求挪到手机上，再把数据流转发给 Supernote，延迟确实降了一截，但日常用起来还是不够跟手。
3. **现在的直连中继方案。** Supernote 在局域网里直接连上 RikkaHub 内置的 Web 服务（或者连上 Notipal 自己托管的轻量网页），使用时保持实时对话流常开。多轮对比测下来，这是响应最快的一套，也就成了今天 AI 中继的底层实现。

AI 中继默认关闭。想用时去设置的**可选工具**里开启，它就会出现在笔记、文档和 Mosaic 的工具栏上。如果你希望自己的墨水屏彻底离线、永远不碰 AI，同一页设置里还留了一枚**永久关闭开关**：打开它之后，AI 中继、Lexi 的 Ask AI 和翻译功能会全部彻底停用，除非卸载重装 Notipal，否则不会再出现。

### 两种连接方式

**配对 RikkaHub（推荐）。** [RikkaHub](https://github.com/rikkahub/rikkahub) 是一款自带 Web 服务功能的安卓 AI 聊天应用。在 RikkaHub 里配好模型和 API Key，打开 **设置 → Web Server**，关闭**身份验证（authentication）**并启动服务。确保手机和 Supernote 连在同一个 Wi-Fi 下，点击 Supernote 上的 **AI 中继**：Notipal 会自动发现手机并请求配对。以后哪怕路由器给手机换了 IP，它也能自己重连上。

- 在 Supernote 上的提问会顺着手机当前打开的对话往下聊，你在手机上问的问题也会同步显示到墨水屏上。
- 语音提问和录音直接调用手机麦克风与 RikkaHub 的语音识别。
- Lexi 和 DocuCraft 的翻译功能也会共用这条连接。

**不用 RikkaHub：由 Supernote 自己开一个网页房间。** 在 AI 中继的设置里把后端切成 **Web page**，Supernote 会在本地起一个网页并弹出二维码。用手机扫一扫，或者把网址输进电脑浏览器，这个标签页就成了中继的另一端。在网页里填好模型服务商的 API Key（支持 OpenAI、Anthropic、Google Gemini、DeepSeek、OpenRouter、硅基流动 SiliconFlow、阿里云百炼、Kimi、火山方舟、xAI、Groq、Mistral，以及任何允许浏览器直接跨域调用的 OpenAI 兼容接口）并选好模型。这些配置保存在 Supernote 本地，以后换台电脑开浏览器也不用重新填一遍 Key。

- 房间网址自带访问口令，拿到链接的人就能消耗你的 API 额度，注意不要外泄；点一下 **New link** 就能立刻让旧链接作废。
- 使用期间请保持这个网页打开。比起过一分钟就自动息屏锁机的手机，电脑浏览器能稳得多。
- 如果提问时已配对的 RikkaHub 手机连不上，Notipal 会询问是否改用网页模式，你可以选仅用这一次，或者干脆永久切换。

### 提问

既可以直接敲字，也可以直接从当前页面抓取内容：

- **按选区提问（Ask from selection）**：圈选一段手写笔迹，它会被识别成文字填进输入框；在文档里选中的文本则原样填入。如果想问一道手写数学题，把模式切到**公式（Formula）**，手写笔迹会以清晰小图的形式附带过去；发完这一条后模式会自动切回纯文本。
- **按区域提问（Ask from area）**：在屏幕上框出任意一块画面作为图片附在问题里。
- **语音（Voice，仅限 RikkaHub）**：对着手机说话，转写出的文字会跟着问题一起发出去。

在你点下发送之前，什么都不会传出去，所以你可以放心拼好一张图、一段摘录再加上几句补充说明，一次问清楚。

### 回复与回写

答复会直接排版好显示在同一个窗口里，完整支持 Markdown 和 LaTeX 公式，下面可以继续追问。展开到全屏后，点**选择（Select）**挑出值得留存的段落再点**插入（Insert）**：在笔记里，正文会变成文本框（沿用你设好的字体和字号），而数学公式会被真实地写成分段单线笔迹落到纸上；在 Mosaic 上，正文变成文本卡片，公式化作白板笔迹；在文档里，选中的内容则直接复制进系统剪贴板。

AI 窗口可以缩成一块浮在手边的小窗，方便一边看答复一边写字，也可以铺满全屏，或者收成一个圆气泡。**收件箱（Inbox）**留存着你的历史对话、录音和收藏夹，并支持全文检索。AI 中继自身的偏好设置（后端、已配对手机、窗口展开方式、半透明效果）都藏在收件箱底部的**设置**里。

## 用 LocalSend 传文件

[LocalSend](https://localsend.org) 是一款免费开源的局域网传输工具，覆盖 Android、iOS、Windows、macOS 和 Linux。Notipal 直接在 Supernote 端内置了 LocalSend 节点，不用插线，也不用借道云盘，设备之间走本地 Wi-Fi 直传。

### 建立连接

1. 在手机或电脑上装好并打开 LocalSend。
2. 确保它和 Supernote 连在同一个 Wi-Fi 下。
3. 打开 Notipal 悬浮工具栏。只要工具栏开着，LocalSend 服务就会在后台自动就绪。

在另一台设备的附近设备列表里，你的平板会显示为 **Supernote**。

### 接收内容

| 另一台设备发来的内容 | 落脚去处 |
| --- | --- |
| 纯文本 | 进入文本接收气泡，或存入系统剪贴板（见[文本接收](#文本接收)） |
| 在打开笔记时收到的单张图片 | Notipal 会弹窗询问：**立即插入**、先收着，还是拒收 |
| 其他图片与常规文件 | `/sdcard/INBOX` |
| 插件安装包（`.snplg`） | `/sdcard/MyStyle` |
| 剪贴板同步数据包 | 经你确认后写入 8 个剪贴板槽位 |

所有收到的图片都会自动出现在插入图片的**来自 LocalSend**分类里。

### 发送内容

从智能套索、截图裁剪器或**分享文档**都能打开发送面板。勾选要传的项目，点选目标设备即可（设备刚亮屏没搜到时，点一下**重新扫描 Rescan**）。长按某台设备可以把它设为**默认设备**：以后传单张图片或单段文字时，不用再挑设备，直接发过去。

### 分享文档

读文档时，点一下副栏的**分享文档（Share Document）**就能把当前文件发出去。如果这是一份写有手写批注的 PDF，且你装了 DocuCraft 插件，Notipal 会先让 DocuCraft 导出带批注的版本再发送。如果没装 DocuCraft、插件版本过旧，或者导出碰上意外，它会自动退回发送原始文件。

### 剪贴板同步

在发送面板里点**同步剪贴板（Sync Clipboard）**，可以把全部 8 个槽位打包发给另一台装了 Notipal 的 Supernote，对方确认后才会覆盖接收。长按智能套索是这个功能的快捷入口：如果设了默认设备就直接同步过去，没设的话就打开发送面板等你选。

## 设置

在 Supernote 的插件管理器里找到 Notipal，点它的设置按钮即可进入：

- **工具栏（Toolbar）**：调整通用工具的排列顺序（先长按某一项再拖动）；开关可选工具（AI 中继、插入链接）；彻底关闭所有 AI 功能。
- **连接（Connections）**：指定默认 LocalSend 设备；选择传出截图时是否启用更锐利的 600 DPI 渲染；配置插入文本框的字体与字号。
- **关于（About）**：查看版本号与反馈入口。

关闭设置界面时，当前开着的悬浮工具栏也会一并关闭。

## Mosaic

有时候思路刚铺开，定宽的笔记页面就已经写到边了。Mosaic 是为 Supernote 打造的无限手写白板：你可以随处落笔、把笔迹圈成卡片、随意挪动和缩放、用连线把想法挂在一起，还能按项目建出任意多块画板。

它完全可以独立运行，不过装上 Notipal 之后才算发挥出全部本事：Notipal 负责把笔记页面、PDF、图片、文字和剪贴板源源不断送进 Mosaic，而 Notipal 自己几乎所有工具也都能直接在白板上用。另外，只要装了 Mosaic，Supernote 原生的笔记和文档应用就会顺带获得两项本来没有的能力：[快速切换](#快速切换)和 [Sketch](#sketch)。

在笔记或文档的工具栏里点 **Mosaic** 图标，或者直接从快速切换器里点选任意画板，就能打开白板。

### 快速切换

装好 Mosaic 之后，在任何笔记或文档页面的底边单指上滑，就能直接跳到另一篇笔记、文档或白板。它直接在 Supernote 原生的笔记和文档应用里工作，不用事先打开 Mosaic，也不用退回文件管理器里翻找。在白板里（哪怕已经点进了笔记卡片）同样管用。

操作时用一根手指从屏幕底边往上滑，避开左右两个底角，并记得把笔拿离屏幕。切换抽屉滑出后有**最近（Recent）**和 **Sketch** 两个标签页，每次都会默认回到你上次停的那一页。

**在笔记或文档里呼出时**，「最近」会把画板和最近打开的文件混在一起，按时间从新到旧排开：

- 画板卡片会显示你离开时的缩略画面，以及里面的卡片数量。
- 文件卡片显示封面和阅读进度，比如“Page 3 / 12”；加密文件会标出一把小锁。
- 点画板直接进 Mosaic，点文件直接回到上次看到的那一页。
- 点 **+ 新建画板（+ New board）** 会沿用当前模板建一块未命名白板并立刻打开。

**在白板里呼出时**，界面会列出所有画板，外加**上一篇笔记（Previous note）**和**上一篇文档（Previous document）**两个快捷入口。点画板切换画布，点快捷入口瞬间退回刚才的文档，或者点**最近文件 ↗（Recent files ↗）**关闭 Mosaic 回到文件列表。

长按任意卡片进入多选模式。点击**删除（Delete）**可以把文件从最近列表里隐藏（下次打开该文件时还会重新出现），或者在二次确认后删除画板。主画板不可删除。

每次唤出切换器前，系统都会先自动保存当前画板。轻点抽屉以外的空白处就能把它收回去。

### Sketch

Sketch 是藏在快速切换器里的一块小手写板，定位就像贴在桌边的一张便利贴。无论你正在看书、写笔记还是整理白板，手指上滑就能记个号码、列条待办或画个草图，点一下外边就回到刚才的工作里。写下的内容会一直留着，下次上滑还在；如果你上次停在 Sketch 标签页，下次唤出也会直接停在这里。

- 它的迷你工具栏里有三个笔槽（书法笔、针管笔或铅笔）、橡皮和套索。再点一次当前选中的笔槽可以换笔型和粗细，这里的预设与主白板互不干扰。
- 双指轻点撤销，三指轻点重做。
- 写下的内容全部自动保存，并作为名为 **Sketch** 的卡片存在卡片盒里。

工具栏最后一格留给了 Notipal。如果装了 Notipal 但工具栏没开，点这格就会把它打开；一旦工具栏开着，这格就变成了共享剪贴板入口：圈着笔迹时点它就能存进指定槽位，没选中时点它就能把槽位里的内容贴进 Sketch。因为 Sketch 的书写比例和标准笔记页完全一致，你从这里拷回笔记里的手写字大小不会变形。

**让 Sketch 和白板打通。** 把白板里某张卡片放进[卡片盒](#画板卡片盒与备份)，在盒子里选中它并点**粘贴到草稿（Paste to sketch）**，上面的笔迹就会拷进 Sketch，而原卡片依然安稳待在盒子里。这么一来，一份公式表、文章提纲或阅读清单就离你平板上的每一篇笔记和每一份 PDF 都只有一次上滑的距离。反过来，在卡片盒里选中 **Sketch** 点**粘贴到画板（Paste to board）**，也能把你随手记下的东西变成一张正式的文本卡片放上白板。

### 白板上的 Notipal

两款插件都装好后，先开 Notipal 再进 Mosaic，工具栏会自动切到白板模式，无论在画布上还是在笔记卡片里都能顺手使用：

- **共享剪贴板。** 8 个槽位贯通笔记、文档、白板和 Sketch。在笔记里圈走一段手写贴上白板，把白板上的草图带回笔记，或者把 Sketch 里记的几行字贴到 PDF 边白，全都能来去自如。从笔记拷来的文本框和图片会自动变成文本卡片与图片卡片。卡片本体不能直接塞进剪贴板，但写在卡片上的手写笔迹可以。
- **插入图片**、排队中的文档截图，以及**智能套索 → Mosaic 卡片**，都会作为图片卡片落在空白处。
- **文本接收**和 **AI 中继**的输出会变成文本卡片落到气泡下方，其中的数学公式则会化作白板笔迹。
- **笔槽**负责切换当前白板笔刷，**墨水调色板**负责给已经画好的笔迹改样式。
- **智能套索**既能把圈住的笔迹识别成文字发走，也能截取屏幕上任意一块区域。
- **导入笔记与文档。** 当 Mosaic 关闭时，在笔记或 PDF 的副栏点**笔记转 Mosaic（Note to Mosaic）**或**文档转 Mosaic（Doc to Mosaic）**，挑好页面就能导入。笔记页面会汇总成一张笔记卡片，笔迹和文本框原样保留；PDF 页面则按 600 DPI 精细渲染成文档卡片，原本的手写批注、高亮和下划线依然保持可编辑状态。有一个平台特性需要提前知道：受 Supernote 系统限制，文件处于当前显示的最后一页时无法读取其数据，导入前请先翻到其他页面。你也可以直接用整份笔记或 PDF 生成一块新白板，每页自动排成一张卡片。

Notipal 在白板上会尽量避免添乱：如果悬浮栏挡住了 Mosaic 自己的胶囊栏或关闭按钮，它会自动挪到空处；当 Mosaic 的侧边菜单、笔刷面板或快速切换器打开时，它会自动藏起来。

### 边读边写（Nomad 与 Manta）

这组功能仅支持 Supernote Nomad 与 Manta，让你能把 Mosaic 直接浮在文档上面，一边看书一边畅快做长笔记，不用在两个应用之间反复切屏。

**画板半透明。** 在侧边菜单里打开**画板半透明（Board translucency）**，底下的文档或笔记就会透出来，拖动滑块可在 10% 到 80% 之间调出最顺眼的透明度。在笔记卡片里写字时，你的笔迹背后会自带一层细微的白色衬底，就算底下是密密麻麻的正文，自己写的字也依然清清楚楚。拖动卡片或者把画布缩到 60% 以下时，画板会临时恢复不透明，方便你看清排布。

**侧滑条翻页。** 开启半透明后，顺手把底下的**侧滑条翻页（Slider page turn）**也打开。之后在机身侧边滑条上往上滑，底下的文档就会翻到下一页，往下滑翻回上一页，在白板和笔记卡片里都有效，Mosaic 全程无需退出。开启此项时，侧滑条用来给文档翻页，不再触发撤销和重做。

**翻页模式（Page turn mode）。** 同样在侧边菜单里，专门针对墨水屏刷新特性做了取舍：开启后，双指手势不再做连续平滑滚动，而是改成步进跳转。双指朝某个方向一滑，视野干脆利落地移动六分之五个屏幕；双指捏合缩放时，也是在手指松开后一次性缩放到位。这能大幅减少残影和局刷次数，浏览大画布的手感更像在翻纸书。在白板和笔记卡片里均适用。

**推荐一套很顺手的精读组合：** 打开一本 PDF，在上面呼出 Mosaic，把半透明和侧滑条翻页都打开。随手画个方框建一张卡片，转成笔记卡片并点进去。里面是一张可以一直往下滚的长竖页：边读边顺着往下写，写多长都不断页，底下书看完了就拨一下侧滑条翻下一页。看书和无限滚动记笔记合在了同一个安静的画面里，用起来很有 reMarkable 的味道。

### 白板操作指南

**工具栏。** 一枚小巧的胶囊里装着「更多」、4 个笔槽、橡皮和套索，关闭按钮单独安静地待在右上角。手指按住胶囊可以拖到任意位置，长按「更多」在横排和竖排之间切换，双指双击页面隐藏胶囊。点「更多」或从屏幕左边缘向内滑拉出侧边菜单，里面有缩放、撤销重做、画板列表、卡片盒、备份、底纹模板、半透明和翻页模式。

**画笔。** 4 个笔槽都可以自由配成书法笔（斜尖，8 档粗细）、针管笔（12 档粗细）、荧光笔（黑、深灰、浅灰或下划线模式），以及带纸面纹理和侧锋倾角响应的铅笔（从 HB 到 8B）。再点一次已选中的笔槽就能打开细调面板。Notipal 的笔槽栏同样可以控制白板画笔，包括那些额外的超粗尺寸。

**书写体验。** 画布、每张卡片以及卡片之间的连桥，都是彼此独立的书写层，所以写在卡片里的字会牢牢跟着卡片走。深色底面上落笔会自动反白。用侧滑条撤销重做，双指捏合缩放（菜单支持 10% 到 400%），双指拖动平移；写字时手掌搭在笔尖旁边不会误拖画布。

**图形识别。** 这里没有专门要你去点的图形工具。画完一条直线、三角形或椭圆，笔尖在终点停顿半秒，它就会自动规整成干净的几何图形。画直线时按住不放，可以带着角度和长度吸附来微调，同时用另一只手指轻点一下就能接着画下一条相连的边；首尾闭合后即成三角形、矩形或多边形。选中规整后的图形，还能进一步吸附成正方形、黄金比例、A 系列纸张比例、正圆、等腰、等边或直角图形，并切换实心或空心。

**卡片交互。**

- 绕着写好的笔迹随手画个方框并停顿半秒，就能把里面的字收成一张卡片。
- 从一张卡片内部落笔，拖到外面空白处抬笔，直接拉出一张相连的新卡片。
- 在卡片边缘画一个括号形状，就能顺着方向拉宽或拉长这张卡片。
- 圈选卡片里的手写字点**识别（Recognize）**，将其转为排版整齐的文本卡片。
- 把笔稳稳按在文本卡片上，呼出键盘进行富文本编辑（标题、粗体、斜体、缩进与字号）。
- 用手指长按卡片即可拎起来移动。套索工具栏支持复制、黑白反色、将文本卡片转为笔记卡片、收入卡片盒或删除。

在文本卡片里使用荧光笔时，它会像文字扫描笔一样自动对齐字行，只高亮或下划线划过的字符，不会涂得歪歪扭扭。

**连接关系。** 拖着一张卡片碰一下同色的另一张卡片，或者直接从旧卡片里拉出新卡片，它们之间就会长出一条会跟着卡片移动而弯曲的连桥。在连桥上写字，两张卡片就会锁定成一组同步移动。如果只想要更轻盈的关联，从一张卡片画条线连到另一张，就会生成一根细连接线。

**三种卡片。** **笔记卡片（Note Cards）**里是一张可无限向下延伸的长竖页，轻点或用笔按住就能进入书写，顶部还能写上手写标题；**文档卡片（Document Cards）**是通过 Notipal 导入的 PDF 页面，保留着可编辑的批注与高亮；**图片卡片（Image cards）**则承接来自 Notipal 的图片、文档截图和智能套索截取的画面。

关于把白板半透明浮在 PDF 上面翻页阅读，详见[边读边写](#边读边写nomad-与-manta)。

**插回笔记。** 当你是从某篇笔记里打开 Mosaic 时，在白板上圈选一块区域再点**插入笔记（Insert into note）**，这片区域就会作为一张带着 Mosaic 标签的图片落回你的笔记本。以后点一下这个标签，就能直接跳回白板上对应的位置；而且每次关闭 Mosaic 时，笔记里的那张缩略图都会自动刷新成最新内容。

### 画板、卡片盒与备份

- **多画板。** 想建多少画板都行，既可以从侧边菜单新建（空白画板、从笔记生成、从文档生成），也能在快速切换器里点 **+ 新建画板**。每块画板独立记住自己的卡片、模板、透明度和视野位置，在快速切换器里就能随手切换或删除。
- **从笔记或文档建画板。** 挑好文件和页码，每一页都会在新画板上自动变成一张笔记卡片或文档卡片。文档需为 PDF 格式；如果你选了其他格式，Notipal 会询问是否先用 DocuCraft 帮忙转换。
- **卡片盒（Card Box）。** 相当于在不同画板之间搬运卡片的收纳箱。选中卡片点盒子图标存进去，切到另一块画板，从侧边菜单打开**卡片盒**点**粘贴到画板**即可。你的 Sketch 草稿纸也常驻在这里。
- **保存与备份。** 关闭 Mosaic、从屏幕顶部下拉、呼出快速切换器或切换画板时，当前画板都会自动保存。侧边菜单里的**备份（Back up）**会把画板导出到 `/sdcard/EXPORT/Notipal/mosaic/`，用**导入画板（Import board）**可以将其恢复成新画板，或者直接覆盖当前画板。

### 开发中特性：Inkflow 与网页同步

这一节介绍的是还在工作台上的新能力。初版已经跑通，目前仍在测试和打磨手感，尚未在正式版里完整开放。

**网页同步（Web sync）。** Mosaic 可以与电脑浏览器里的配套网页白板联机：既可以在侧边菜单输入电脑的局域网地址直连，也可以通过可分享的在线房间链接连通。笔迹、卡片和连线全都会实时同步，而且落笔第一点就会立刻出现在电脑屏幕上，不用等你写完一笔。如果两边同时修改画板，Mosaic 会按逐张卡片、逐条笔迹的粒度自动合并冲突。网页端也完整复刻了同样的笔刷、橡皮、套索和手势：画框建卡、画括号拉大卡片、拉出相连新卡、画连线以及停顿规整图形。

**Inkflow（数位板模式）。** Inkflow 能把 Supernote 变成一块支持压感和倾角的数位板，直接操控电脑浏览器上的网页白板。在平板文档里选中一段文字，点选择栏里的 **Inkflow**：这段文字会立刻作为文本卡片出现在电脑白板上，同时平板文档下半部分会弹出一个操控视窗。该视窗通过 USB 完整映射到整个电脑屏幕，笔尖悬停在玻璃上方时，电脑上的光标就会跟着精准移动。

- 视窗上方有一条小工具栏，提供画笔（书法笔、针管笔或荧光笔）、橡皮、套索和移动工具，状态与电脑网页端实时同步。
- 双指平移和缩放电脑上的白板，单指选取、拖动和调整电脑上的卡片大小。
- 写字时手掌可以放心搭在屏幕上。机身侧滑条负责撤销和重做；如果开启了翻页模式，则直接用来翻动底下的文档页面。
- 使用 Inkflow 需要电脑浏览器已连上网页同步并打开白板，同时用 USB 数据线连接电脑，且**关闭 ADB 调试**。点视窗外侧或从顶部下拉即可退出。

---

# Notipal

People pick up a Supernote because they want a quiet place to read and think on paper. Most of the time, the built-in Notes and Documents apps honor that well, right up until you find yourself digging through three menus just to swap pens, or losing a sentence in your head while backing out to the file list to check another PDF.

Notipal is a toolkit plugin for **Supernote tablets** built to smooth out those small daily interruptions without turning your tablet into a noisy computer. It adds a compact floating toolbar to Notes and Documents: eight persistent clipboards, a one-tap pen bar, document cropping and page stitching, image insertion, local sharing over LocalSend, and direct document export.

It grew up alongside **Mosaic**, a handwriting whiteboard plugin by the same author. Because the two were built side by side, almost every Notipal tool works right on the Mosaic canvas too. Mosaic itself is closed source and released next to Notipal; its complete guide lives further down in this README under [Mosaic](#mosaic).

One ground rule runs through all of it: **everything stays on your local network.** Notipal runs no cloud service of its own, asks for no account, and puts no middleman between your notebooks and your other devices.

## Highlights

- **[Quick Switch](#quick-switch) in Notes and Documents.** With Mosaic installed, swipe up from the bottom edge of any note or document page. A strip of your recent notes, PDFs, and boards slides up, and one tap takes you straight there. It runs inside Supernote's native Notes and Documents apps, so you never have to open Mosaic first.
- **[Sketch](#sketch), a scratch pad kept within thumb's reach.** Inside that same quick switcher sits a small handwriting space that behaves like a sticky note. Swipe up from whatever you're reading or writing, jot down a phone number or a passing thought, and tap away. You can also park a whiteboard card here to keep an outline or formula in view while working in another file.
- **[Screenshots](#document-screenshots) built for real reading.** Crop a passage from a PDF, stitch consecutive pages into a tall strip or a neat grid, or frame any region on screen with [Smart Lasso](#smart-lasso). Queue crops to drop into a note later, beam them to your phone, or land them on the Mosaic whiteboard as image cards.
- **[Smart Lasso](#smart-lasso) understands what you circle.** Lassoed handwriting goes out as recognized text, text boxes and document selections travel as clean text, and a link to another file sends the linked document itself, all over LocalSend.
- **[Pen Slots](#pen-slots).** A slim vertical bar for switching pens, erasers, and the lasso in one tap, including thicker nibs and wider erasers than the stock Supernote menu provides.
- **[AI Relay](#ai-relay), built around E Ink's pace.** Ask about a handwritten paragraph, a cropped figure, or a handwritten equation, and watch the reply stream onto the page from RikkaHub on your phone or from a browser tab opened by scanning a QR code.
- **[Read and write on top](#read-and-write-on-top-nomad-and-manta) (Nomad and Manta).** Float a translucent Mosaic board over a document, take notes inside an endlessly scrolling Note Card, and turn the pages underneath with the side slider without closing a thing, much like a reMarkable.
- **[Mosaic](#notipal-on-the-whiteboard) ties the pieces together.** One set of eight clipboards moves freely between notes, documents, Sketch, and the whiteboard. Note and PDF pages import as cards; screenshots, incoming text, and AI replies land directly on the board.

## Quick Start

### Get the App

- **InkHub**: Search for Notipal and Mosaic in InkHub on your Supernote and install them directly.
- **GitHub**: Grab the latest `.snplg` packages for Notipal and Mosaic from [GitHub Releases](https://github.com/Not-Just-Supernote/sn-plugin-notipal/releases), copy them into `/sdcard/MyStyle/` on the tablet, and install them like any other Supernote plugin.
- **Source**: Prefer to compile it yourself? See [build Notipal from source](#build-from-source).

### What You Need

| What you want to use | What you need |
| --- | --- |
| Toolbar, clipboards, pen slots, screenshots | A Supernote with plugin support |
| Sending and receiving files or text | Wi-Fi, plus [LocalSend](https://localsend.org) on your other device |
| Exporting a PDF with your handwritten notes intact | The DocuCraft plugin |
| AI Relay | An Android phone running RikkaHub, or any browser with a model provider API key on the same Wi-Fi |
| Whiteboard, Quick Switch, and Sketch | The Mosaic plugin |

### First Launch

Notipal meets you in three places:

- **Note toolbar**: Tap the Notipal (Inkling) button to open or close the floating toolbar in Notes.
- **Document toolbar**: The same button toggles it in Documents.
- **Lasso menu**: Tap **Clipboard** in either Notes or Documents to save your current selection straight to a slot.

On first launch, Notipal asks for file read, write, and delete permissions along with network access. Pick **Always allow** for each one. Because Notipal stays on standby in the background, a one-time grant expires quietly and stops tools from working later.

## The Toolbar

The toolbar is laid out as a main column of tools beside a narrow rail holding your clipboard slots and a few quick controls. Drag it anywhere with your finger to get it out of your writing path. When you tap a button with your pen, it triggers the tool without leaving stray ink on the page underneath.

Its buttons adapt to the app you're in:

| Where you are | Main column | Side rail |
| --- | --- | --- |
| Note | Clipboard Manager, Insert Image, Smart Lasso, Pen Slots, AI Relay\*, Insert Link\* | Clipboards, Text Receive, Note to Mosaic, Collapse |
| Document | Clipboard Manager, Doc Screenshot, Smart Lasso, Pen Slots, AI Relay\* | Clipboards, Share Document, Doc to Mosaic, Collapse |
| Mosaic | Clipboard Manager, Insert Image, Smart Lasso, Pen Slots, AI Relay\* | Clipboards, Text Receive, Ink Palette, Collapse |

\* Optional tools. AI Relay and Insert Link stay hidden until you turn them on in Settings.

Insert Image and Doc Screenshot share the same spot: it captures the screen when you are reading a document, and drops images in when you are in a note or on the whiteboard.

**Collapse** tucks the toolbar against the nearest screen edge as a small handle. A two-finger double tap on the page does the same. Tap the handle, or swipe it back toward the middle, to bring the toolbar out again. Long press the handle to close Notipal completely.

It also knows when to step aside on its own. The toolbar collapses whenever the AI window or Lexi's small window is open, and hides completely while a Mosaic menu, the Mosaic quick switcher, or Lexi's full-screen window is on screen.

## Features

### Clipboards

A single system clipboard is fragile when you are rearranging notes. Notipal gives you eight persistent slots along the side rail. Filled slots invert to white on black so you can tell at a glance what is loaded; if all eight don't fit at once, swipe up or down along the numbers to scroll through them.

**Saving.** In a note or document, lasso what you want, tap **Clipboard** in the lasso menu, and pick a slot. Handwriting, shapes, text boxes, images, and links all save cleanly (the one exception is a selection containing a title, which cannot be saved). On the Mosaic whiteboard, lasso strokes and tap a slot number directly. It asks before overwriting an occupied slot, and long pressing an empty slot cuts instead of copying (saving the strokes, then lifting them off the canvas).

**Pasting.** With nothing selected, tap any filled slot. In a note, the pasted item lands back where it came from, nudging itself into nearby open space if something is already sitting there. It arrives lasso-selected so you can slide it into place immediately.

| Pasting into | What arrives |
| --- | --- |
| Note | Everything saved in the slot |
| Document | Handwriting and shapes only; slots containing text boxes, images, or links are refused |
| Mosaic | Handwriting centered in view; text boxes become text cards and images become image cards; slots with links are refused |

**Clipboard Manager** opens a visual grid of all eight slots. Tap a preview to paste it (or to save into it on Mosaic), or long press to inspect it full screen. **Clear clipboard** in the bottom left empties every slot after asking first. To wipe just one slot, long press its number on the toolbar.

These eight slots are shared across notes, documents, Mosaic, and Sketch, and you can send the entire set over to a second Supernote. See [Clipboard Sync](#clipboard-sync).

### Pen Slots

Pen Slots puts a vertical pen bar right on the page, inspired by the Kindle Scribe, so you can switch tools without opening Supernote's pen menu and breaking your rhythm.

Tap **Pen Slots** to bring it up. The top cell folds it away, the bottom gear opens **Pen Slot Settings**, and the middle cells hold up to nine tools with each tip width printed in the corner.

- **Tap a cell** to switch your current note or document pen to that preset. The active cell inverts, and it stays in step even if you pick a different pen from Supernote's own menu.
- **Tap the active cell again** to bounce back to whichever tool you were using right before.
- **Fold** shrinks the bar down to a single square; tap it to open again. A two-finger double tap folds it too, and you can drag the bar anywhere with your finger.

**Pen Slot Settings** (tap the gear, or long press Pen Slots) lets you configure all nine slots. Each one can be a needle pen, ink pen, calligraphy pen, marker, eraser, or lasso, paired with a color (black, dark gray, light gray, or white) and thickness. Beyond the standard Supernote widths, the slots reach noticeably wider:

| Tool | Widest built-in | Pen Slots options |
| --- | --- | --- |
| Needle pen | 2.0 | Up to 3.0 |
| Ink pen | 2.0 | Up to 5.0 |
| Calligraphy | 2.0 | 3.0, 4.0, 5.0, 6.0, 7.0, 7.5 |
| Marker | One size | 4.0, 5.0, 6.0 |
| Eraser | 2.5 | 0.7, 1.9, 3.3, 5.0 |

Two small caveats to keep in mind: Pen Slots only changes the pen you are about to write with (strokes already on the page stay as they are), and the pen icon on Supernote's own sidebar won't update to reflect a pen chosen from this bar.

On the Mosaic whiteboard, the bar switches the whiteboard's pen instead, pulling from Ink Palette's nine slots (stored separately from your note and document presets). To restyle strokes already on the whiteboard, use [Ink Palette](#ink-palette-mosaic).

### Smart Lasso

Smart Lasso adapts to what is in your hand. Its icon changes depending on whether you have an active selection.

**With a selection, it shares over LocalSend.** Notipal inspects what you selected and opens the Send panel:

| What you selected | What gets sent |
| --- | --- |
| Handwriting | Text recognized by Supernote's handwriting engine |
| Text boxes | Their plain text |
| A link to a note or document | The linked file itself |
| A web link | The URL |
| Document text (text tool or highlighter) | The text as is |
| Strokes or text cards on Mosaic | Recognized text, or the cards' text |

Recognized handwriting and text boxes keep their reading order from the page. If you have set a default device in LocalSend, a single piece of text fires straight over without extra confirmation.

**Without a selection, it captures any area of the screen.** Draw loosely around whatever you want to grab with your pen. A crop frame snaps around it; drag its edges, corners, or center handle to refine the bounds, then choose where it goes:

- **Send to devices**: Send the cropped image over LocalSend.
- **Add to Doc Screenshots**: File it under Screenshots in [Insert Image](#insert-image) for dropping into a note later.
- **Mosaic card**: Turn it into an image card on Mosaic and jump right into the whiteboard.

This works on any screen: a note, a document, or the whiteboard. If you get distracted and leave the frame sitting idle, it dismisses itself after 30 seconds.

Long pressing Smart Lasso acts as a shortcut to sync your clipboards to another Supernote. See [Clipboard Sync](#clipboard-sync).

### Document Screenshots

Reading deeply often means clipping diagrams, quotes, and tables as you go, then assembling them in a notebook later. Notipal handles three kinds of screenshots and funnels all of them into the same place:

- **Page screenshot**: Capture the current document page and crop out what matters.
- **Long screenshot**: Stitch several pages into one continuous strip or a grid.
- **Area screenshot**: Frame any part of any screen with [Smart Lasso](#smart-lasso).

While reading a document, you can queue several crops without leaving the page. When you switch back to your notebook, each tap on Insert Image drops the next queued crop straight onto the page.

**In a document, tap Doc Screenshot** to freeze the page and open the crop editor. Frame what you want, then pick an action:

| Action | What happens |
| --- | --- |
| Insert Next | Adds the crop to your insertion queue; each tap on Insert Image in a note places the next one |
| Add to Queue | Saves the crop to the Screenshots list in Insert Image (keeps the last 20) |
| Send to Other Devices | Sends the crop immediately over LocalSend |
| Stitch | Begins a multi-page stitch, or appends this page to the one in progress |

Toggle **Multi** on if you want to cut several separate excerpts from the same page without closing the editor between crops.

**Stitching** merges pages into a single image. Capture a page, turn to the next one, and capture again. Once two pages are collected, the stitch editor opens:

- **Long** stacks pages into a tall vertical strip. Drag any seam to fine-tune the overlap, or tap **Auto Align** to let Notipal line them up.
- **Grid** arranges the pages into columns, ordered either across rows or down columns.
- **Add Page**, **Remove Last**, and **Reverse** let you adjust the sequence on the fly.

An unfinished stitch stays saved for 15 minutes in case you step away.

**In a note, the same button becomes Insert Image.** If you have a crop waiting in the queue, tapping it drops the image right onto the page. When the queue is empty, it opens the image picker. Long press the button to open the picker at any time, even with crops queued.

### Insert Image

Insert Image opens a full-screen browser sorted by source along the left edge:

- **Recent**: The latest images from all folders below, along with Supernote's own system screenshots.
- **Screenshots**: Your document screenshots, with queued ones listed first.
- **From LocalSend**: Images beamed over from your other devices.
- **Folders**: Document, EXPORT, MyStyle, Note, SCREENSHOT, and INBOX.
- **Trash**, tucked at the bottom.

Tap images to select them, mixing across sources if needed; a small number on each thumbnail tracks the order they will be placed. From there:

- **Insert** (arrow icon) drops them into the note one by one, leaving each selected so you can position it.
- **Crop & Insert** (for a single image) trims the frame and lets you apply a filter (**Original**, **Enhance**, or **B&W Text**) with ink density tunable from 100% down to 20%.
- **Delete** (bin icon) sends selected images to the Trash, where you can restore them anytime within 30 days before they are permanently removed.

Queued screenshots and images from LocalSend are cleared out automatically once you insert them, keeping your workspace tidy. On Mosaic, inserted images arrive as image cards laid out side by side in open space.

### Text Receive

Sometimes typing a long quote, URL, or citation on a physical keyboard beats writing it out by hand. Text Receive lets you drop text from your phone or computer straight onto your Supernote page.

1. Tap **Text Receive** in the side rail to place a small target bubble on the page.
2. Drag the bubble to the spot where you want the text to land.
3. Send text over LocalSend from your phone or computer.

Each incoming message becomes a text box right at the bubble's coordinates. Follow-up messages stack cleanly below it, stepping around anything already written on the page. Tap the bubble to place waiting text, or long press it to discard what's queued.

It works in both Notes and Mosaic (where each message becomes a text card). Where incoming text lands depends on what is currently open:

| What is on screen | Where the text goes |
| --- | --- |
| A note or whiteboard, unobstructed, with Text Receive on | Directly to the Text Receive bubble |
| The same, with Text Receive off | System clipboard, plus a prompt asking if you want to turn Text Receive on |
| A document, anywhere outside Notes, or any overlay covering the page (a Supernote menu or dialog, another plugin window, a Notipal panel, the expanded AI window, a Mosaic menu or quick switcher, or Lexi) | System clipboard |

Because fallback text lands on the system clipboard, you can still paste it from the keyboard into any search bar or text field while you are in the middle of typing.

To customize how inserted text looks, head to **Inserted text boxes** in Settings to pick the font and size. Notipal reads custom `.ttf` and `.otf` fonts from `/sdcard/Document/Fonts/`.

### Insert Link

An optional tool for Notes, enabled under **Optional Tools** in Settings. It opens a file browser where tapping a document inserts a link styled just like Supernote's native links. Turn on multi-select to drop several links at once.

### Ink Palette (Mosaic)

On the Mosaic whiteboard, Ink Palette sits in the side rail for restyling strokes you have already written. Lasso your strokes and tap it to open the full panel and adjust pen type, color, or width. Tap it with nothing selected to float a compact bubble holding your nine presets; from there, lasso strokes and tap a preset to restyle them in one move. Every change registers cleanly in Mosaic's undo history.

In Notes and Documents, Pen Slots takes this spot.

## AI Relay

Cramming a full AI chat client onto an E Ink tablet rarely feels good. The tablet's processor lags behind the stream, the screen stutters, and you end up waiting on your notebook instead of thinking. AI Relay takes a different route, treating the Supernote as a focused display and pen interface while letting a faster device on your desk do the heavy lifting.

- The Supernote only sends the prompt and typesets the answer. The actual API call happens from your phone or a browser tab over local Wi-Fi, with no Notipal server in the middle.
- While you are actively using it, the connection stays open so words stream onto the page the moment the model produces them.
- Prompts come straight off your page: recognized handwriting, a cropped diagram, or handwritten math go into the query without retyping.
- When you pause to read or write, the link rests to spare battery and wakes instantly when you ask again. Replies that finish while it's resting are never dropped.

**Why it is built this way.** This architecture came out of six months of building and benchmarking three competing designs side by side:

1. **Direct API calls from the Supernote.** The simplest design and comfortably the slowest. Every TLS handshake and every incoming chunk had to squeeze through the tablet's own network stack and CPU.
2. **A phone relaying the stream.** Moving the model call to a phone and piping chunks back to the Supernote shaved off latency, yet it still felt sluggish in everyday use.
3. **The current relay.** The Supernote connects directly over LAN to RikkaHub's local web server (or to a lightweight browser page Notipal serves itself) and subscribes to the live conversation stream while active. Across repeated side-by-side tests, this came out clearly the fastest, and it is what AI Relay runs today.

AI Relay starts off by default. Turn it on under **Optional Tools** in Settings to add it to the toolbar in Notes, Documents, and Mosaic. And if you prefer your tablet to stay strictly offline and AI-free, the same settings page includes a permanent off switch: it disables AI Relay, Lexi's Ask AI, and translation for good. Only uninstalling and reinstalling Notipal brings them back.

### Two Ways to Connect

**Pair with RikkaHub (recommended).** [RikkaHub](https://github.com/rikkahub/rikkahub) is an Android AI chat app with a built-in web server. Configure your model and API key in RikkaHub, open **Settings → Web Server**, turn **authentication** off, and start the server. With both devices on the same Wi-Fi, tap **AI Relay** on the Supernote: Notipal spots your phone on the network and asks to pair. After that it reconnects automatically, even if your router hands the phone a new IP address.

- Questions pick up right inside the conversation open on your phone, and anything you ask on the phone appears on the Supernote too.
- Voice prompts and recordings use your phone's microphone and RikkaHub's speech recognition.
- Lexi and DocuCraft translations share the same connection.

**Without RikkaHub: host a room from the Supernote.** Switch the backend to **Web page** in AI Relay's settings. The Supernote spins up a local page and displays a QR code. Scan it on a phone, or open the address in a computer browser, and that tab becomes the model side of the relay. Enter an API key for your provider (OpenAI, Anthropic, Google Gemini, DeepSeek, OpenRouter, SiliconFlow, Alibaba Bailian, Kimi, Volcengine Ark, xAI, Groq, Mistral, or any OpenAI-compatible endpoint that permits browser calls) and choose a model. Your provider settings stay saved on the Supernote, so switching browsers later doesn't mean typing keys all over again.

- The room URL includes an access code. Anyone holding that link can spend your API quota, so treat it as private; **New link** immediately revokes the old one.
- Keep the browser tab alive while working. A laptop or desktop browser stays awake much more reliably than a phone screen that locks after a minute.
- If your paired RikkaHub phone is unreachable when you ask a question, Notipal offers to fall back to the web page, either just for that session or permanently.

### Asking

Type directly into the input box, or pull material straight off your page:

- **Ask from selection**: Lasso handwriting to convert it into text in the input box, or select document text to drop it in verbatim. Switch the mode to **Formula** to attach handwritten math strokes as a crisp small image instead; the toggle resets to text after one message.
- **Ask from area**: Frame any region of the screen and attach it as an image.
- **Voice** (RikkaHub only): Speak into your phone; the transcript attaches to your prompt.

Nothing leaves the tablet until you tap send, so you can gather a diagram, a paragraph, and a note together before asking.

### Replies

Answers arrive in the same window with Markdown and LaTeX properly typeset, ready for follow-up questions below. Expand to full screen, **Select** the lines worth keeping, and tap **Insert**: in a note, prose lands as text boxes (using your chosen font and size) while equations are actually drawn onto the page as single-line pen strokes. On Mosaic, prose becomes text cards with equations rendered as strokes on the board. In a document, selected lines copy straight to the system clipboard.

You can keep AI Relay floating as a compact window beside your writing, expand it full screen, or tuck it into a small round bubble. The **Inbox** holds past conversations, voice recordings, and favorites with full-text search. AI Relay's own preferences (backend, paired phone, window launch mode, and translucency) sit under **Settings** at the bottom of the Inbox.

## Sharing with LocalSend

[LocalSend](https://localsend.org) is a free, open-source tool for moving files and text between nearby devices on Android, iOS, Windows, macOS, and Linux. Notipal builds a LocalSend node directly into the Supernote side, so transfers move straight over your Wi-Fi with zero cables or cloud staging.

### Get Connected

1. Install and launch LocalSend on your phone or computer.
2. Put it on the same Wi-Fi network as your Supernote.
3. Open the Notipal toolbar. The LocalSend service runs automatically while the toolbar is open.

Your tablet shows up as **Supernote** in LocalSend's nearby device list.

### Receiving

| What you send | Where it lands |
| --- | --- |
| Text | The Text Receive bubble, or the system clipboard (see [Text Receive](#text-receive)) |
| A single image while a note is open | Notipal asks whether to **Insert now**, keep it for later, or reject it |
| Other images and general files | `/sdcard/INBOX` |
| A plugin package (`.snplg`) | `/sdcard/MyStyle` |
| A clipboard sync | Your eight clipboard slots, after you confirm |

Incoming images also appear under **From LocalSend** inside Insert Image.

### Sending

The Send panel opens from Smart Lasso, the screenshot crop editor, and **Share Document**. Check the items you want to transfer and tap a target device (hit **Rescan** if a device just woke up). Long press any device to mark it as your **default**: single images and text snippets will then go straight there with no extra picker step.

### Share Document

While reading a document, tap **Share Document** in the side rail to send the file over LocalSend. If it is a PDF with your handwritten annotations and you have the DocuCraft plugin installed, Notipal asks DocuCraft to render a PDF with your notes baked in and sends that copy instead. If DocuCraft isn't installed, is too old, or hits an export snag, Notipal falls back cleanly and sends the original file.

### Clipboard Sync

Tapping **Sync Clipboard** in the Send panel bundles all eight slots and sends them to Notipal on another Supernote, which prompts before overwriting its own slots. Long pressing Smart Lasso is a quick shortcut: it syncs immediately to your default device, or opens the Send panel ready to go.

## Settings

Open Settings from Notipal's entry inside the Supernote plugin manager.

- **Toolbar**: Drag to reorder the shared tools (long press a row first), toggle optional tools (AI Relay, Insert Link), or permanently turn AI off.
- **Connections**: Choose a default LocalSend device, enable sharper 600 DPI rendering for outgoing screenshots, and configure the font and size for inserted text boxes.
- **About**: Version details and feedback links.

Closing Settings also closes the active toolbar.

## Mosaic

Sometimes a fixed-width notebook page runs out of room right when an idea starts branching out. Mosaic is an open handwriting whiteboard for Supernote where you can write anywhere, gather strokes into cards, move and resize them, link thoughts with bridges, and maintain as many separate boards as you need.

It runs well on its own, though it comes alive alongside Notipal: Notipal feeds pages, images, text, and clipboards into Mosaic, and nearly all of Notipal's tools operate right on the whiteboard. Installing Mosaic also gives Supernote's native Notes and Documents apps two abilities they lack out of the box: [Quick Switch](#quick-switch) and [Sketch](#sketch).

Launch Mosaic from the **Mosaic** button in the Note or Document toolbar, or jump straight into a board from the quick switcher.

### Quick Switch

Once Mosaic is installed, you can swipe up from the bottom edge of any note or document page to hop straight to another note, document, or board. It runs inside Supernote's native Notes and Documents apps (Mosaic doesn't need to be open), sparing you the round trip through the file manager. It works inside Mosaic too, even while zoomed into a Note Card.

Use one finger to swipe up from the bottom edge, starting clear of the left and right corners, and keep your pen away from the glass while swiping. The switcher glides up from below with two tabs, **Recent** and **Sketch**, remembering whichever one you used last.

**From a note or document**, Recent blends your boards and recent files together, newest first:

- Board cards preview the board as you left it alongside its card count.
- File cards show the cover and your reading position, such as "Page 3 / 12". Encrypted files display a lock icon.
- Tap any board to open Mosaic there, or tap a file to resume right on the page you left.
- **+ New board** spins up an untitled board using your current template and opens it immediately.

**From the whiteboard**, it displays all your boards plus quick return buttons for **Previous note** and **Previous document**. Tap a board to switch, tap either shortcut to return to your file, or hit **Recent files ↗** to close Mosaic and view the recent file list.

Long press a card to enter selection mode. **Delete** hides files from the Recent strip (they reappear next time you open them) and removes boards after asking for confirmation. The main board cannot be deleted.

Opening the switcher automatically saves the current board first. Tap anywhere outside the drawer to close it.

### Sketch

Sketch is a small handwriting area tucked inside the quick switcher, meant to behave like a sticky note on the corner of your desk. Swipe up from any note, document, or board, scribble down a phone number, reminder, or rough diagram, and tap outside to get right back to your work. Whatever you wrote stays waiting the next time you swipe up, and the switcher reopens on Sketch if that was the last tab you used.

- Its mini-toolbar holds three pen slots (calligraphy pen, fineliner, or pencil), an eraser, and a lasso. Tap an active slot again to adjust its pen type and width. These presets are kept separate from the main whiteboard.
- Tap with two fingers to undo, three fingers to redo.
- Everything saves automatically and lives in the Card Box as **Sketch**.

The final cell belongs to Notipal. If Notipal is installed but closed, tapping it opens the toolbar. Once the toolbar is running, that cell acts as your shared clipboard: with strokes lassoed, pick a slot to save them; with nothing selected, pick a slot to paste into Sketch. Because Sketch matches the scale of a standard note page, handwriting you copy out of Sketch lands in a regular note at the exact size you wrote it.

**Moving between Sketch and the whiteboard.** Drop any card into the [Card Box](#boards-card-box-and-backup), select it there, and tap **Paste to sketch**. Its handwriting copies into Sketch while the original card stays safe in the box. That puts a formula sheet, project outline, or reading checklist one swipe away from every note and PDF on your tablet. Going the other direction, pick **Sketch** in the Card Box and tap **Paste to board** to promote your quick jottings into a text card on the whiteboard.

### Notipal on the Whiteboard

With both plugins installed, start Notipal and open Mosaic: the toolbar shifts into its Mosaic layout automatically, working both on the open canvas and inside Note Cards.

- **Shared clipboards.** All eight slots bridge notes, documents, Mosaic, and Sketch. Lasso handwriting in a note and drop it on a board, carry strokes from a board back into a notebook, or move a few lines from Sketch into a PDF margin. Note text boxes and images become text cards and image cards on Mosaic. Cards themselves cannot be stored in clipboard slots, though the handwriting written on them can.
- **Insert Image**, queued document screenshots, and **Smart Lasso → Mosaic card** land as image cards in nearby open space.
- **Text Receive** and **AI Relay** outputs arrive as text cards beneath the bubble, with math formulas drawn as strokes.
- **Pen Slots** switches the active whiteboard pen, while **Ink Palette** restyles strokes you lasso.
- **Smart Lasso** recognizes and sends lassoed strokes, or crops any patch of the screen.
- **Import notes and documents.** Tap **Note to Mosaic** or **Doc to Mosaic** in the side rail of a note or PDF (while Mosaic is closed) to pull chosen pages onto the board. Note pages merge into a single Note Card preserving both strokes and text boxes. PDF pages render cleanly at 600 DPI into Document Cards, keeping your handwritten notes, highlights, and underlines editable. One platform quirk to know: due to a Supernote system limit, the last page of a file cannot be read while it is currently displayed on screen, so flip to another page before importing it. You can also generate a brand-new board straight from a note or PDF, laid out as one card per page.

Notipal stays considerate of Mosaic's interface: if the toolbar ever overlaps Mosaic's own capsule or close button, it nudges itself into open space, and hides whenever a Mosaic menu, pen panel, or the quick switcher opens.

### Read and Write on Top (Nomad and Manta)

Available exclusively on the Supernote Nomad and Manta, these tools let you keep Mosaic layered directly over a document so you can read and take long-form notes without flipping back and forth between apps.

**Translucency.** Switch on **Board translucency** in the side menu to let the underlying document or note show through the canvas; drag the slider to dial the opacity between 10% and 80%. Inside a Note Card, your handwriting carries a subtle white backing so your own words stay crisp even over dense print underneath. The board temporarily turns opaque while you drag cards around, or whenever you zoom out below 60%.

**Slider page turn.** With translucency active, enable **Slider page turn** right beneath it. Swiping up along the hardware side slider flips the underlying document to the next page, and swiping down flips back, working both on the whiteboard and inside Note Cards while Mosaic stays open. While this option is on, the side slider controls document pages rather than undo and redo.

**Page turn mode.** Also found in the side menu, this option trades continuous two-finger scrolling for discrete jumps designed around E Ink: a two-finger swipe shifts the viewport by five-sixths of a screen in that direction, and a pinch zooms in one clean step once you lift your fingers. That cuts down partial refreshes dramatically, making panning feel closer to turning physical pages. It works on the whiteboard and inside Note Cards.

**A reading setup worth trying:** Open a PDF, launch Mosaic over it, and enable both translucency and slider page turn. Draw a rough rectangle to spawn a card, convert it into a Note Card, and open it. Inside, you get a single continuous vertical sheet: write down the page as far as your thoughts run, scrolling freely, and flick the side slider whenever you're ready for the next page of the book underneath. Reading and endless vertical note-taking stay in one quiet view, much like a reMarkable.

### Whiteboard

**Toolbar.** A compact capsule holds More, four pen slots, the eraser, and the lasso, while the close button sits cleanly alone at the top right. Drag the capsule anywhere, long press More to flip between horizontal and vertical layouts, or double tap with two fingers to hide it. Tap More (or swipe inward from the left edge) for the side menu: zoom, undo and redo, boards, Card Box, backup, templates, translucency, and page turn mode.

**Pens.** Each slot can hold a calligraphy pen (slanted nib, 8 widths), a fineliner (12 widths), a marker (black, dark gray, light gray, or underline), or a pencil (HB through 8B, complete with paper grain and tilt response). Tap an already-selected slot to open its tuning panel. Notipal's Pen Slots bar also drives the whiteboard pen, including its extra-wide sizes.

**Writing.** The canvas, every card, and every bridge between cards act as independent writing surfaces, so strokes drawn on a card travel with it. Ink automatically flips to white over dark backgrounds. Use the side slider for undo and redo, pinch to zoom (10% to 400% from the menu), and drag with two fingers to pan. Resting your palm near the pen won't drift the canvas.

**Shapes.** There is no separate shape tool to hunt for. Just pause your pen for half a second at the end of a stroke, and a line, triangle, or ellipse snaps clean. Keep holding on a straight line to adjust its angle and length with snapping, then tap with a finger to start the next connected segment; closing the loop forms a triangle, rectangle, or polygon. Once selected, shapes can be snapped to square, golden ratio, A-paper proportions, circular, isosceles, equilateral, or right-angled geometry, and toggled between filled and hollow.

**Cards.**

- Draw a rough rectangle around existing strokes and pause to wrap them into a card.
- Begin a stroke inside a card and finish on empty canvas to pull out a new connected card.
- Draw a bracket along a card's edge to stretch its width or height.
- Lasso handwriting and tap **Recognize** to convert it into a typed text card.
- Hold your pen still on a text card to edit it with the keyboard (headings, bold, italic, indents, and font size).
- Long press a card with your finger to pick it up and move it. Lasso buttons let you copy, flip color (white or black), promote a text card into a Note Card, file it in the Card Box, or delete it.

Inside cards, the marker behaves like a smart text highlighter, snapping cleanly to typed lines and highlighting or underlining only the characters it passes across.

**Connections.** Slide a card until it touches another card of the same color, or pull a new card straight out of an existing one, to link them with a flexible bridge that bends as you rearrange them. Writing directly on a bridge locks both cards into a group. To link two cards more lightly, draw a stroke from one to the other for a thin connecting thread.

**Note Cards** open into a tall vertical notebook page; tap one, or hold your pen on it, to step inside, scroll down as far as you need, and add a handwritten title at the top. **Document Cards** hold PDF pages imported through Notipal with editable handwritten annotations and highlights. **Image cards** hold pictures, cropped screenshots, and Smart Lasso captures from Notipal.

For floating Mosaic over a PDF and flipping pages underneath, see [Read and Write on Top](#read-and-write-on-top-nomad-and-manta).

**Insert into a note.** Whenever you open Mosaic from a note, lasso any area on the board and tap **Insert into note**. It drops into your notebook as an image carrying a Mosaic tag. Tap that tag later to jump straight back to that exact spot on the canvas; the thumbnail in your note refreshes automatically whenever you close Mosaic.

### Boards, Card Box, and Backup

- **Boards.** Create as many boards as your projects require, either from the side menu (blank, from a note, or from a document) or via **+ New board** in the quick switcher. Each board remembers its own cards, background template, translucency level, and viewport. Switch or remove boards right from the quick switcher.
- **New board from a note or document.** Pick a file and choose which pages to bring in; each page turns into a Note Card or Document Card on the new board. Documents need to be PDFs, and if you pick another format, Notipal offers to convert it through DocuCraft first.
- **Card Box.** A carrying case for moving cards across boards. Select a card and tap the box button to stow it, switch to another board, open **Card Box** from the side menu, and tap **Paste to board**. Your Sketch pad lives here too.
- **Saving and Backup.** Boards save automatically whenever you close Mosaic, pull down from the top, open the quick switcher, or switch boards. **Back up** in the side menu exports a board to `/sdcard/EXPORT/Notipal/mosaic/`, and **Import board** brings a backup back either as a new board or over the current one.

### In Development: Inkflow and Web Sync

Everything in this last section is active workbench code. Early builds are running and being tuned, though they are not yet fully shipped in release builds.

**Web sync.** Mosaic can pair with a companion whiteboard in a desktop browser, either directly over your LAN (type your computer's local address in the side menu) or through a shareable online room link. Strokes, cards, and connections sync live, with ink appearing on the browser canvas from the very first pen point while your hand is still moving. If you edit on both screens at the same time, Mosaic merges changes card by card and stroke by stroke. The web board mirrors the same pens, eraser, lasso, and pen gestures: wrap strokes into a card, expand a card with a bracket, pull out a connected card, draw a thread, or pause to snap a shape.

**Inkflow (drawing tablet mode).** Inkflow turns your Supernote into a pressure- and tilt-sensitive pen tablet wired to the web whiteboard on your computer. Select a passage of text in a document and tap **Inkflow** in the selection bar: the passage lands on your computer's whiteboard as a text card, while a control window opens across the lower half of your document. That window maps to your entire computer screen over USB, so the cursor tracks your pen even while hovering above the glass.

- A small floating bar above the window holds your pen (calligraphy, fineliner, or marker), eraser, lasso, and move tool, staying synced with the browser whiteboard.
- Two fingers pan and zoom the desktop canvas; one finger selects, moves, and resizes cards.
- Rest your palm comfortably on the screen while writing. The side slider handles undo and redo, or flips the underlying document pages when page turn mode is active.
- Inkflow requires an active web sync session with the whiteboard open in your computer's browser, a USB cable linking the two, and ADB debugging turned off. Tap outside the window or pull down from the top to close it.