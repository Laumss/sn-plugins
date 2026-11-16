import { I18nManager } from 'react-native';
import { log } from './common';
import { hasNativeModule, notipalCommon } from './native';

export type Locale = 'zh' | 'en' | 'ja' | 'zh-TW';

type Entry = Record<Locale, string>;

const STRINGS = {
  perm_required:          { zh: '需要权限', en: 'Permission Required', ja: '権限が必要です', 'zh-TW': '需要權限' },
  initial_permissions_required: {
    zh: 'Notipal 需要文件读写、删除和网络权限。请为每项权限选择”始终允许”，完成后再次点击 Notipal。',
    en: 'Notipal requires permanent file read/write/delete and network access. Select Always allow for every permission, then tap Notipal again.',
    ja: 'Notipalにはファイルの読み書き、削除、ネットワークの権限が必要です。各項目で「常に許可」を選んでから、もう一度Notipalをタップしてください。',
    'zh-TW': 'Notipal 需要檔案讀寫、刪除和網路權限。請為每項權限選擇「始終允許」，完成後再次點擊 Notipal。',
  },

  ai_selection_retry:     { zh: '请在当前页重新圈选内容', en: 'Select content again on the current page', ja: 'このページで内容を囲み直してください', 'zh-TW': '請在目前頁面重新圈選內容' },

  bubble_ai_recognizing:  { zh: '识别中…', en: 'Recognizing…', ja: '認識中…', 'zh-TW': '辨識中…' },
  bubble_ai_waiting:      { zh: '等待 AI 回复', en: 'Waiting for AI', ja: 'AIの返答を待っています', 'zh-TW': '等待 AI 回覆' },
  bubble_ai_no_lasso:     { zh: '请先套索选中文字', en: 'Select text with lasso first', ja: '先に投げ縄でテキストを選んでください', 'zh-TW': '請先用套索選取文字' },
  lasso_send_ocr_failed:  { zh: '无法识别所选笔迹内容', en: 'Unable to recognize the selected handwriting', ja: '選択した筆跡を認識できませんでした', 'zh-TW': '無法辨識所選筆跡內容' },
  lasso_send_no_text:     { zh: '未识别出文字', en: 'No text recognized', ja: '文字は認識されませんでした', 'zh-TW': '未辨識出文字' },
  smart_lasso_picture_blocked: { zh: '选区里有图片，智能套索无法发送，请只选笔迹或文字', en: 'Smart Lasso cannot send a selection that contains pictures. Select only handwriting or text.', ja: '選択範囲に画像があるため、スマート投げ縄では送信できません。筆跡またはテキストだけを選んでください。', 'zh-TW': '選取範圍裡有圖片，智慧套索無法傳送，請只選筆跡或文字' },
  bubble_ai_timeout:      { zh: 'AI 无响应，请检查中转站', en: 'AI timed out · check the relay', ja: 'AIが応答しません。中継を確認してください', 'zh-TW': 'AI 無回應，請檢查中繼站' },

  doc_text_receive_stop:  { zh: '已进入文档，文本接收悬浮窗已关闭，后续接收到的文本会添加到输入法剪贴板中', en: 'Opened a document and closed the text receive bubble. Text received from now on goes to the keyboard clipboard', ja: 'ドキュメントに切り替わり、テキスト受信バブルを閉じました。以降のテキストはキーボードのクリップボードに入ります', 'zh-TW': '已進入文件，文字接收懸浮窗已關閉，後續接收到的文字會加到輸入法剪貼簿中' },
  doc_text_receive_unsupported: { zh: '文档中不支持文本接收', en: 'Text receiving is not available in documents', ja: 'ドキュメントではテキスト受信に対応していません', 'zh-TW': '文件中不支援文字接收' },
  bubble_doc_insert_unsupported: { zh: '文档中不能插入回复', en: "Can't insert replies into documents", ja: 'ドキュメントには返答を挿入できません', 'zh-TW': '文件中無法插入回覆' },
  bubble_tap_to_insert: { zh: '已放进文本气泡，点气泡插入', en: 'In the text bubble, tap it to insert', ja: 'テキストバブルに入れました。タップで挿入', 'zh-TW': '已放進文字氣泡，點氣泡插入' },

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
  clip_links_unknown:     { zh: '保存这一格时没能确认是否含有链接，只能粘贴到笔记里；不含链接的话请重新保存这一格', en: 'Could not confirm whether this slot contains links when it was saved, so it can only be pasted into notes. If it has no links, save it again', ja: 'このスロットは保存時にリンクの有無を確認できなかったため、ノートにしか貼り付けられません。リンクがない場合はもう一度保存してください', 'zh-TW': '儲存這一格時沒能確認是否含有連結，只能貼到筆記裡；不含連結的話請重新儲存這一格' },
  clip_empty_hint:        { zh: '剪贴板为空，请先用套索选中内容保存', en: 'Clipboard is empty. Select content with lasso first.', ja: 'クリップボードは空です。先に投げ縄で内容を選んで保存してください。', 'zh-TW': '剪貼簿為空，請先用套索選取內容儲存' },
  doc_last_page_lasso:    { zh: '宿主限制：文档最后一页无法读取套索内容，剪贴板保存暂不可用', en: 'Host limitation: lasso content on the last document page cannot be read, so clipboard save is unavailable there', ja: 'ホスト側の制限：ドキュメントの最終ページでは投げ縄の内容を読み取れないため、クリップボード保存は使えません', 'zh-TW': '宿主限制：文件最後一頁無法讀取套索內容，剪貼簿儲存暫不可用' },
  clip_mosaic_unsupported: { zh: '这一格有 Mosaic 不支持的内容（如旧版笔型的字迹、几何图形），贴不进 Mosaic。大片内容请回到笔记或文档，用侧栏的「导入到 Mosaic」', en: 'This slot has content Mosaic cannot take (such as strokes from older pen types, or shapes), so it cannot be pasted into Mosaic. For large areas, go back to the note or document and use Import to Mosaic in the sidebar.', ja: 'このスロットにはMosaicが扱えない内容（旧型のペンの筆跡、図形など）が含まれているため、Mosaicに貼り付けられません。広い範囲はノートかドキュメントに戻り、サイドバーのMosaic取り込みを使ってください。', 'zh-TW': '這一格有 Mosaic 不支援的內容（如舊版筆型的字跡、幾何圖形），貼不進 Mosaic。大片內容請回到筆記或文件，用側欄的「匯入到 Mosaic」' },
  clip_err_read_failed:   { zh: '读取套索内容失败，请稍候重试', en: 'Failed to read lasso content. Please try again.', ja: '投げ縄の内容を読み取れませんでした。もう一度試してください。', 'zh-TW': '讀取套索內容失敗，請稍候重試' },
  clip_save_failed:       { zh: '剪贴板保存失败，请重试', en: 'Clipboard save failed, please retry', ja: 'クリップボードの保存に失敗しました。もう一度試してください', 'zh-TW': '剪貼簿儲存失敗，請重試' },
  clip_use_lasso_menu:    { zh: '为了更快的速度与兼容性，请使用套索菜单里的「剪贴板」按钮保存（Mosaic 白板插件内除外）', en: 'For speed and compatibility, save with the Clipboard button in the lasso menu (except inside the Mosaic whiteboard plugin)', ja: 'より速く安定して使うため、投げ縄メニューの「クリップボード」ボタンから保存してください（Mosaicホワイトボード内は除く）', 'zh-TW': '為了更快的速度與相容性，請使用套索選單裡的「剪貼簿」按鈕儲存（Mosaic 白板外掛內除外）' },
  clip_lasso_button:      { zh: '剪贴板', en: 'Clipboard', ja: 'クリップボード', 'zh-TW': '剪貼簿' },
  btn_cancel:             { zh: '取消', en: 'Cancel', ja: 'キャンセル', 'zh-TW': '取消' },
  btn_confirm:            { zh: '确定', en: 'OK', ja: 'OK', 'zh-TW': '確定' },

  feature_not_ready:      { zh: '这个功能在当前版本里还不能用', en: 'This feature is not available in this build yet', ja: 'この機能はこのバージョンではまだ使えません', 'zh-TW': '這個功能在目前版本裡還不能用' },
} satisfies Record<string, Entry>;

export type StringKey = keyof typeof STRINGS;

export function localeFromTag(tag: string): Locale {
  const low = tag.toLowerCase();
  if (low.startsWith('ja')) { return 'ja'; }
  if (low.startsWith('zh')) {
    if (low.includes('tw') || low.includes('hant') || low.includes('hk')) { return 'zh-TW'; }
    return 'zh';
  }
  return 'en';
}

function detectLocale(): Locale {
  try {
    const lang = I18nManager.getConstants().localeIdentifier;
    if (lang) { return localeFromTag(lang); }
  } catch (e) {
    log.w('i18n', 'system locale unavailable, using en', e);
  }
  return 'en';
}

let current: Locale = detectLocale();

export function getLocale(): Locale {
  return current;
}

export function setLocale(loc: Locale): void {
  current = loc;
  if (!hasNativeModule('NotipalCommon')) {
    log.w('i18n', 'NotipalCommon missing, native strings keep their locale');
    return;
  }
  try {
    notipalCommon.setLocale(loc);
  } catch (e) {
    log.w('i18n', 'setLocale on native failed', e);
  }
}

export function t(key: StringKey, ...args: (string | number)[]): string {
  const entry: Entry | undefined = STRINGS[key];
  if (!entry) { return String(key); }
  const template = entry[current] || entry.en;
  if (args.length === 0) { return template; }
  const names: string[] = [];
  return template.replace(/\{(\w+)\}/g, (whole, name: string) => {
    let i = names.indexOf(name);
    if (i < 0) {
      i = names.length;
      names.push(name);
    }
    return i < args.length ? String(args[i]) : whole;
  });
}
