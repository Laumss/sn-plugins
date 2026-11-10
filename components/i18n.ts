import { NativeModules, Platform } from 'react-native';

type Locale = 'zh' | 'en' | 'ja' | 'zh-TW';

const STRINGS = {

  perm_required:          { zh: '需要权限', en: 'Permission Required', ja: '権限が必要です', 'zh-TW': '需要權限' },
  initial_permissions_required: {
    zh: 'Notipal 需要文件读写、删除和网络权限。请为每项权限选择”始终允许”，完成后再次点击 Notipal。',
    en: 'Notipal requires permanent file read/write/delete and network access. Select Always allow for every permission, then tap Notipal again.',
    ja: 'Notipalにはファイルの読み書き、削除、ネットワークの権限が必要です。各項目で「常に許可」を選んでから、もう一度Notipalをタップしてください。',
    'zh-TW': 'Notipal 需要檔案讀寫、刪除和網路權限。請為每項權限選擇「始終允許」，完成後再次點擊 Notipal。',
  },
  perm_always_required: {
    zh: '部分权限未设置为”始终允许”。Notipal 需要始终权限才能在后台正常运行，请在接下来的弹窗中选择”始终允许”。',
    en: 'Some permissions are not set to Always allow. Notipal needs permanent access to work properly. Please select Always allow in the following dialogs.',
    ja: '一部の権限が「常に許可」になっていません。Notipalはバックグラウンドで動くため常時権限が必要です。次のダイアログで「常に許可」を選んでください。',
    'zh-TW': '部分權限未設定為「始終允許」。Notipal 需要永久權限才能在背景正常運作，請在接下來的彈窗中選擇「始終允許」。',
  },
  perm_desc_file_read: {
    zh: 'Notipal 需要读取笔记与文档以提供快捷工具',
    en: 'Notipal needs read access to notes and documents for the toolbar',
    ja: 'ノートとドキュメントを読み取ってツールバーを提供します',
    'zh-TW': 'Notipal 需要讀取筆記與文件以提供快捷工具',
  },
  perm_desc_file_write: {
    zh: 'Notipal 需要写入存储以保存剪藏与配置',
    en: 'Notipal needs storage write access to save clips and settings',
    ja: 'クリップやSettings（設定）を保存するためにストレージへの書き込みが必要です',
    'zh-TW': 'Notipal 需要寫入儲存空間以儲存剪藏與設定',
  },
  perm_desc_file_delete: {
    zh: 'Notipal 需要删除存储中的临时导出文件',
    en: 'Notipal needs storage delete access for temporary export files',
    ja: '一時エクスポートファイルを削除するために必要です',
    'zh-TW': 'Notipal 需要刪除儲存空間中的暫存匯出檔案',
  },
  perm_desc_internet: {
    zh: 'Notipal 需要访问局域网以提供 LocalSend 发送与接收',
    en: 'Notipal needs LAN access for LocalSend send and receive',
    ja: 'LocalSendの送受信のためにLANアクセスが必要です',
    'zh-TW': 'Notipal 需要存取區域網路以提供 LocalSend 傳送與接收',
  },

  tool_lasso_send:        { zh: '发送选区', en: 'Send Lasso', ja: '選択範囲を送信', 'zh-TW': '傳送選取範圍' },
  ai_mode_text:           { zh: '文字提问', en: 'Text query', ja: 'テキスト質問', 'zh-TW': '文字提問' },
  ai_mode_formula:        { zh: '公式处理', en: 'Formula', ja: '数式処理', 'zh-TW': '公式處理' },
  ai_mode_choose:         { zh: '选择处理方式', en: 'Selection mode', ja: '処理方法を選択', 'zh-TW': '選擇處理方式' },
  ai_selection_hint:      { zh: '用笔圈选内容后确认', en: 'Circle content with the pen, then confirm', ja: 'ペンで内容を囲んでから確定', 'zh-TW': '用筆圈選內容後確認' },
  ai_selection_retry:     { zh: '请在当前页重新圈选内容', en: 'Select content again on the current page', ja: 'このページで内容を囲み直してください', 'zh-TW': '請在目前頁面重新圈選內容' },

  bubble_recv_nospacing:  { zh: '无间距接收中', en: 'Receiving (No Gap)', ja: 'スペースなしで受信中', 'zh-TW': '無間距接收中' },
  bubble_recv_paragraph:  { zh: '段落接收中', en: 'Receiving (Paragraph)', ja: '段落受信中', 'zh-TW': '段落接收中' },
  bubble_ai_recognizing:  { zh: '识别中…', en: 'Recognizing…', ja: '認識中…', 'zh-TW': '辨識中…' },
  bubble_ai_sending:      { zh: '发送中…', en: 'Sending…', ja: '送信中…', 'zh-TW': '傳送中…' },
  bubble_ai_waiting:      { zh: '等待 AI 回复', en: 'Waiting for AI', ja: 'AIの返答を待っています', 'zh-TW': '等待 AI 回覆' },
  bubble_ai_no_lasso:     { zh: '请先套索选中文字', en: 'Select text with lasso first', ja: '先に投げ縄でテキストを選んでください', 'zh-TW': '請先用套索選取文字' },
  lasso_send_ocr_failed:  { zh: '无法识别所选笔迹内容', en: 'Unable to recognize the selected handwriting', ja: '選択した筆跡を認識できませんでした', 'zh-TW': '無法辨識所選筆跡內容' },
  smart_lasso_picture_blocked: { zh: '选区里有图片，智能套索无法发送，请只选笔迹或文字', en: 'Smart Lasso cannot send a selection that contains pictures. Select only handwriting or text.', ja: '選択範囲に画像があるため、スマート投げ縄では送信できません。筆跡またはテキストだけを選んでください。', 'zh-TW': '選取範圍裡有圖片，智慧套索無法傳送，請只選筆跡或文字' },
  bubble_formula_rendering: { zh: '渲染公式中…', en: 'Rendering formula…', ja: '数式をレンダリング中…', 'zh-TW': '算繪公式中…' },
  bubble_formula_failed:  { zh: '公式处理失败', en: 'Formula failed', ja: '数式処理に失敗しました', 'zh-TW': '公式處理失敗' },
  formula_no_relay:       { zh: 'AIRelay 未连接，请确认手机端已启动并可访问', en: 'AIRelay is not connected. Confirm the phone endpoint is running and reachable.', ja: 'AIRelayが接続されていません。スマートフォン側が起動していてアクセスできるか確認してください。', 'zh-TW': 'AIRelay 未連線，請確認手機端已啟動並可存取' },
  bubble_ai_timeout:      { zh: 'AI 无响应，请检查中转站', en: 'AI timed out · check the relay', ja: 'AIが応答しません。中継を確認してください', 'zh-TW': 'AI 無回應，請檢查中繼站' },

  bubble_mosaic_ready:    { zh: '等待文本…点此放卡片', en: 'Waiting for text… tap to place card', ja: 'テキスト待ち…タップでカードを配置', 'zh-TW': '等待文字…點此放卡片' },
  bubble_mosaic_pending:  { zh: '已收到文本，点此创建卡片', en: 'Text received · tap to create card', ja: 'テキストを受信しました。タップでカードを作成', 'zh-TW': '已收到文字，點此建立卡片' },
  note_switched_stop:     { zh: '笔记已切换，文本接收已停止', en: 'Note switched, text receiving stopped', ja: 'ノートが切り替わり、テキスト受信を停止しました', 'zh-TW': '筆記已切換，文字接收已停止' },
  doc_text_receive_stop:  { zh: '已进入文档，文本接收悬浮窗已关闭，后续接收到的文本会添加到输入法剪贴板中', en: 'Opened a document and closed the text receive bubble. Text received from now on goes to the keyboard clipboard', ja: 'ドキュメントに切り替わり、テキスト受信バブルを閉じました。以降のテキストはキーボードのクリップボードに入ります', 'zh-TW': '已進入文件，文字接收懸浮窗已關閉，後續接收到的文字會加到輸入法剪貼簿中' },
  doc_text_receive_unsupported: { zh: '文档中不支持文本接收', en: 'Text receiving is not available in documents', ja: 'ドキュメントではテキスト受信に対応していません', 'zh-TW': '文件中不支援文字接收' },
  bubble_doc_insert_unsupported: { zh: '文档中不能插入回复', en: “Can't insert replies into documents”, ja: 'ドキュメントには返答を挿入できません', 'zh-TW': '文件中無法插入回覆' },
  bubble_tap_to_insert: { zh: '已放进文本气泡，点气泡插入', en: 'In the text bubble, tap it to insert', ja: 'テキストバブルに入れました。タップで挿入', 'zh-TW': '已放進文字氣泡，點氣泡插入' },
  bubble_doc_formula_unsupported: { zh: '文档中不支持公式识别', en: 'Formula mode is not available in documents', ja: 'ドキュメントでは数式認識に対応していません', 'zh-TW': '文件中不支援公式辨識' },
  pages_changed_stop:     { zh: '页面结构变更，文本接收已停止', en: 'Page structure changed, text receiving stopped', ja: 'ページ構成が変わり、テキスト受信を停止しました', 'zh-TW': '頁面結構變更，文字接收已停止' },

  tool_screenshot_send:   { zh: '截图发送', en: 'Screenshot Send', ja: 'スクリーンショット送信', 'zh-TW': '截圖傳送' },

  tool_toggle_spacing:    { zh: '切换间距', en: 'Toggle Gap', ja: 'スペース切替', 'zh-TW': '切換間距' },
  tool_ink_palette:       { zh: '笔迹调色板', en: 'Ink Palette', ja: 'ストロークパレット', 'zh-TW': '筆跡調色盤' },

  doc_link_main_only:     { zh: '请切换到主图层后再插入文档链接', en: 'If you want to insert a document link, you should switch to the main layer first', ja: 'ドキュメントリンクを挿入するには、先にメインレイヤーに切り替えてください', 'zh-TW': '請切換到主圖層後再插入文件連結' },

  clip_err_title:         { zh: '标题暂不支持剪贴板保存', en: 'Titles cannot be saved to clipboard', ja: 'タイトルはクリップボードに保存できません', 'zh-TW': '標題暫不支援剪貼簿儲存' },
  clip_err_link:          { zh: '链接暂不支持剪贴板保存', en: 'Links cannot be saved to clipboard', ja: 'リンクはクリップボードに保存できません', 'zh-TW': '連結暫不支援剪貼簿儲存' },
  clip_err_textbox:       { zh: '文本框暂不支持剪贴板保存', en: 'Text boxes cannot be saved to clipboard', ja: 'テキストボックスはクリップボードに保存できません', 'zh-TW': '文字方塊暫不支援剪貼簿儲存' },
  clip_err_image:         { zh: '图片暂不支持剪贴板保存', en: 'Images cannot be saved to clipboard', ja: '画像はクリップボードに保存できません', 'zh-TW': '圖片暫不支援剪貼簿儲存' },
  clip_err_card:          { zh: 'Mosaic 卡片暂不支持剪贴板保存，卡片上的手写可以', en: 'Mosaic cards cannot be saved to clipboard; the handwriting on them can', ja: 'Mosaicカードはクリップボードに保存できません。カード上の手書きは保存できます', 'zh-TW': 'Mosaic 卡片暫不支援剪貼簿儲存，卡片上的手寫可以' },
  clip_err_unsupported:   { zh: '{types}暂不支持剪贴板保存', en: '{types} cannot be saved to clipboard', ja: '{types}はクリップボードに保存できません', 'zh-TW': '{types}暫不支援剪貼簿儲存' },
  clip_saved_with_skipped:{ zh: '已跳过{skipped}，其余内容已添加到剪贴板', en: 'Skipped {skipped}; the remaining content was added to the clipboard', ja: '{skipped}をスキップし、残りをクリップボードに追加しました', 'zh-TW': '已跳過{skipped}，其餘內容已加到剪貼簿' },
  clip_skip_title:        { zh: '标题', en: 'titles', ja: 'タイトル', 'zh-TW': '標題' },
  clip_skip_image:        { zh: '图片', en: 'images', ja: '画像', 'zh-TW': '圖片' },
  clip_skip_link:         { zh: '链接', en: 'links', ja: 'リンク', 'zh-TW': '連結' },
  clip_skip_textbox:      { zh: '文本框', en: 'text boxes', ja: 'テキストボックス', 'zh-TW': '文字方塊' },
  clip_skip_card:         { zh: 'Mosaic 卡片', en: 'Mosaic cards', ja: 'Mosaicカード', 'zh-TW': 'Mosaic 卡片' },
  clip_skip_other:        { zh: '其他不可保存元素', en: 'other unsupported elements', ja: 'その他の保存できない要素', 'zh-TW': '其他不可儲存元素' },
  clip_skip_separator:    { zh: '、', en: ', ', ja: '、', 'zh-TW': '、' },
  clip_overwrite:         { zh: '剪贴板已有内容，是否覆盖？', en: 'Clipboard slot is not empty. Overwrite?', ja: 'クリップスロットにデータがあります。上書きしますか？', 'zh-TW': '剪貼簿已有內容，是否覆蓋？' },
  clip_doc_ink_only:      { zh: '文档里只能粘贴字迹，这一格含有文本框或图片', en: 'Documents accept handwriting only; this slot contains text boxes or images', ja: 'ドキュメントには筆跡しか貼り付けられません。このスロットにはテキストボックスか画像が含まれています', 'zh-TW': '文件裡只能貼上字跡，這一格含有文字方塊或圖片' },
  clip_links_note_only:   { zh: '这一格含有链接，只能粘贴到笔记里', en: 'This slot contains links and can only be pasted into notes', ja: 'このスロットにはリンクが含まれており、ノートにしか貼り付けられません', 'zh-TW': '這一格含有連結，只能貼到筆記裡' },
  clip_empty_hint:        { zh: '剪贴板为空，请先用套索选中内容保存', en: 'Clipboard is empty. Select content with lasso first.', ja: 'クリップボードは空です。先に投げ縄で内容を選んで保存してください。', 'zh-TW': '剪貼簿為空，請先用套索選取內容儲存' },
  doc_last_page_lasso:    { zh: '宿主限制：文档最后一页无法读取套索内容，剪贴板保存和调色盘暂不可用', en: 'Host limitation: lasso content on the last document page cannot be read, so clipboard save and Palette are unavailable there', ja: 'ホスト側の制限：ドキュメントの最終ページでは投げ縄の内容を読み取れないため、クリップボード保存とパレットは使えません', 'zh-TW': '宿主限制：文件最後一頁無法讀取套索內容，剪貼簿儲存和調色盤暫不可用' },
  palette_ink_only:       { zh: 'Palette 只对手写笔迹生效', en: 'Palette only works on handwriting', ja: 'パレットは手書き筆跡にのみ有効です', 'zh-TW': 'Palette 只對手寫筆跡生效' },
  palette_need_lasso:     { zh: '请先圈选要换笔的笔迹', en: 'Lasso the strokes to restyle first', ja: '先にスタイルを変えたい筆跡を投げ縄で囲んでください', 'zh-TW': '請先圈選要換筆的筆跡' },
  palette_apply_failed:   { zh: '调色盘处理失败，请重新圈选后重试', en: 'Palette update failed. Select the strokes again and retry.', ja: 'パレットの適用に失敗しました。筆跡を囲み直してもう一度試してください。', 'zh-TW': '調色盤處理失敗，請重新圈選後重試' },
  clip_err_read_failed:   { zh: '读取套索内容失败，请稍候重试', en: 'Failed to read lasso content. Please try again.', ja: '投げ縄の内容を読み取れませんでした。もう一度試してください。', 'zh-TW': '讀取套索內容失敗，請稍候重試' },
  clip_save_failed:       { zh: '剪贴板保存失败，请重试', en: 'Clipboard save failed, please retry', ja: 'クリップボードの保存に失敗しました。もう一度試してください', 'zh-TW': '剪貼簿儲存失敗，請重試' },
  clip_use_lasso_menu:    { zh: '为了更快的速度与兼容性，请使用套索菜单里的「剪贴板」按钮保存（Mosaic 白板插件内除外）', en: 'For speed and compatibility, save with the Clipboard button in the lasso menu (except inside the Mosaic whiteboard plugin)', ja: 'より速く安定して使うため、投げ縄メニューの「クリップボード」ボタンから保存してください（Mosaicホワイトボード内は除く）', 'zh-TW': '為了更快的速度與相容性，請使用套索選單裡的「剪貼簿」按鈕儲存（Mosaic 白板外掛內除外）' },
  clip_lasso_button:      { zh: '剪贴板', en: 'Clipboard', ja: 'クリップボード', 'zh-TW': '剪貼簿' },
  btn_cancel:             { zh: '取消', en: 'Cancel', ja: 'キャンセル', 'zh-TW': '取消' },
  btn_confirm:            { zh: '确定', en: 'OK', ja: 'OK', 'zh-TW': '確定' },

  no_wifi:                { zh: '未连接 WiFi', en: 'WiFi is not connected', ja: 'WiFi未接続', 'zh-TW': '未連線 WiFi' },
  localsend_started:      { zh: 'LocalSend 已开启', en: 'LocalSend started', ja: 'LocalSendが開始しました', 'zh-TW': 'LocalSend 已開啟' },
  localsend_stopped:      { zh: 'LocalSend 已关闭', en: 'LocalSend stopped', ja: 'LocalSendが停止しました', 'zh-TW': 'LocalSend 已關閉' },

};

type StringKey = keyof typeof STRINGS;

function detectLocale(): Locale {
  try {
    let lang: string | undefined;
    if (Platform.OS === 'ios') {
      lang = NativeModules.SettingsManager?.settings?.AppleLocale
           || NativeModules.SettingsManager?.settings?.AppleLanguages?.[0];
    } else {
      lang = NativeModules.I18nManager?.localeIdentifier;
    }
    if (lang) {
      const low = lang.toLowerCase();
      if (low.startsWith('ja')) return 'ja';
      if (low.startsWith('zh')) {
        if (low.includes('tw') || low.includes('hant') || low.includes('hk')) return 'zh-TW';
        return 'zh';
      }
    }
  } catch {}
  return 'en';
}

let _locale: Locale = detectLocale();

function syncNativeLocale(loc: Locale): void {
  try {
    NativeModules.FloatingToolbar?.setLocale?.(loc);
  } catch {}
}

export function getLocale(): Locale {
  return _locale;
}

export function setLocale(loc: Locale): void {
  if (loc !== _locale) {
    _locale = loc;
  }
  syncNativeLocale(_locale);
}

export function t(key: StringKey, params?: Record<string, string | number>): string {
  const entry = STRINGS[key];
  let s = entry ? (entry as any)[_locale] || entry.en : String(key);
  if (params) {
    for (const k of Object.keys(params)) {
      s = s.replace(new RegExp(`\\{${k}\\}`, 'g'), String(params[k]));
    }
  }
  return s;
}
