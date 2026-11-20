# Notipal

Notipal is a toolkit plugin for **Supernote tablets**.

It adds a floating toolbar to Notes and Documents. From there you can keep things on eight clipboards, switch between your favourite pens from a pen bar, take document screenshots and stitch them, insert images, send content to other devices with LocalSend, and share the document you're reading.

Notipal is built together with **Mosaic**, a handwriting whiteboard plugin for Supernote by the same author. Almost every Notipal tool also works on the Mosaic whiteboard. Mosaic is not open source; it is released alongside Notipal, and its documentation lives in this README: see [Mosaic](#mosaic).

## Highlights

- **[Quick Switch](#quick-switch) in Notes and Documents.** Install Mosaic and swipe up from the bottom edge of any note or document page: a strip of your recent notes, documents, and boards slides up, and one tap takes you there. It works in the Supernote's own Notes and Documents apps, with no need to open Mosaic first.
- **[Sketch](#sketch), a scratch pad that's always one swipe away.** The quick switcher has a handwriting area for jotting things down like a sticky note, from whatever note or document you're in. Keep a card from the whiteboard there to look at while you work elsewhere.
- **[Screenshots](#document-screenshots) of every kind.** Crop a document page, stitch pages into a long screenshot or a grid, or frame any area of the screen with [Smart Lasso](#smart-lasso). Queue them for inserting into a note, send them to your phone, or drop them on the Mosaic whiteboard as cards.
- **[Smart Lasso](#smart-lasso) shares what you select.** Lassoed handwriting is recognized as text, text boxes and document text are sent as text, and a link sends the document it points to, all over LocalSend.
- **[Pen Slots](#pen-slots).** A vertical pen bar for switching pens, eraser, and lasso in one tap, with pens and erasers thicker than the built-in ones.
- **[AI Relay](#ai-relay), the fastest way to use AI on a Supernote.** Ask about your handwriting, a screenshot, or handwritten math, and the reply streams onto the page from RikkaHub on your phone, or from a browser page you open by scanning a QR code on the Supernote.
- **[Read and write on top](#read-and-write-on-top-nomad-and-manta)** (Nomad and Manta). Lay a translucent Mosaic board over a document, take notes in an endlessly scrolling Note Card, and turn the document's pages with the side slider without closing anything, much like a reMarkable.
- **[Mosaic](#notipal-on-the-whiteboard) works with all of it.** Shared clipboards between notes, documents, the whiteboard, and Sketch; note and PDF pages imported as cards; screenshots, received text, and AI replies placed straight onto the board.

Everything happens on your local network. Notipal has no cloud service of its own.

## Quick Start

### Get the App

- **InkHub**: find Notipal and Mosaic in InkHub, built into the Supernote, and install them from there.
- **GitHub**: download the latest `.snplg` files for Notipal and Mosaic from [GitHub Releases](https://github.com/Not-Just-Supernote/sn-plugin-notipal/releases), copy them to `/sdcard/MyStyle/` on the Supernote, and install them the way you install any plugin.
- **Source**: [build Notipal from source](#build-from-source).

### What You Need

| For                                         | You need                                                                                          |
| ------------------------------------------- | ------------------------------------------------------------------------------------------------- |
| The toolbar, clipboards, pen slots, screenshots | A Supernote with plugin support                                                               |
| Sending and receiving                       | Wi-Fi, and [LocalSend](https://localsend.org) on the other device                                 |
| Sharing a PDF with its handwritten notes    | The DocuCraft plugin                                                                              |
| AI Relay                                    | An Android phone running RikkaHub, or any browser plus an API key from a model provider, on the same Wi-Fi |
| Mosaic features                             | The Mosaic plugin                                                                                 |

### First Launch

Notipal has three entry points:

- **Note toolbar**: the Notipal (Inkling) button opens or closes the floating toolbar in Notes.
- **Document toolbar**: the same button opens or closes it in Documents.
- **Lasso menu**: **Clipboard** saves the lasso selection to a clipboard slot, in Notes and in Documents.

The first time you open it, Notipal asks for file read, write, and delete access, and for network access. Choose **Always allow** for every one. Notipal runs in the background, so a one-time grant stops working.

## The Toolbar

The toolbar has a column of tools and a narrow side column with the clipboard slots and a few small buttons. Drag it with your finger to move it anywhere on the screen. The pen can tap its buttons and won't leave ink on the page underneath.

What it shows depends on where you are:

| Where    | Tools                                                                 | Side column                                           |
| -------- | --------------------------------------------------------------------- | ----------------------------------------------------- |
| Note     | Clipboard Manager, Insert Image, Smart Lasso, Pen Slots, AI Relay\*, Insert Link\* | Clipboards, Text Receive, Note to Mosaic, Collapse |
| Document | Clipboard Manager, Doc Screenshot, Smart Lasso, Pen Slots, AI Relay\* | Clipboards, Share Document, Doc to Mosaic, Collapse   |
| Mosaic   | Clipboard Manager, Insert Image, Smart Lasso, Pen Slots, AI Relay\*   | Clipboards, Text Receive, Ink Palette, Collapse       |

\* Optional. AI Relay and Insert Link are off until you turn them on in the settings.

Insert Image and Doc Screenshot are one button: it inserts images in a note or on the whiteboard, and captures the screen in a document.

**Collapse** docks the toolbar to the nearest screen edge as a small handle. Double tap the page with two fingers to do the same. Tap the handle, or swipe it toward the middle of the screen, to bring the toolbar back. Long press the handle to close Notipal completely.

The toolbar also gets out of the way by itself: it collapses while the AI window is open or Lexi's small window is up, and hides while a Mosaic menu, the Mosaic quick switcher, or Lexi's full-screen window is open.

## Features

### Clipboards

Notipal has eight clipboard slots. They are numbered in the side column, and a filled slot shows white on black. When not all eight fit, swipe up or down on the numbers to see the rest.

**Saving.** In a note or a document, lasso something and tap **Clipboard** in the lasso menu, then pick a slot. Handwriting, shapes, text boxes, images, and links are saved; a selection with a title can't be. On the Mosaic whiteboard, lasso strokes and tap a slot number: it asks before it overwrites, and a long press on an empty slot cuts (saves, then deletes the strokes).

**Pasting.** With nothing selected, tap a filled slot. In a note the content goes back to where it was copied, moved to nearby free space if something is already there. It comes back lasso-selected so you can drag it right away.

| Paste into | What arrives                                                                   |
| ---------- | ------------------------------------------------------------------------------ |
| Note       | Everything                                                                     |
| Document   | Handwriting and shapes. Slots with text boxes, images, or links are refused    |
| Mosaic     | Handwriting in the middle of the view. Text boxes become text cards and images become image cards. Slots with links are refused |

**Clipboard Manager** opens the eight slots with their previews: tap one to paste it (or to save into it on Mosaic). Long press a slot to see it full screen. **Clear clipboard** at the bottom left empties all of them after asking. To clear a single slot, long press its number on the toolbar.

The same eight slots are shared with Mosaic and with Mosaic's Sketch, and you can copy them all to another Supernote. See [Clipboard Sync](#clipboard-sync).

### Pen Slots

Pen Slots gives you a vertical pen bar, in the style of the Kindle Scribe, for switching pens without opening the Supernote pen menu.

Tap **Pen Slots** to show the bar. The top cell folds it, the bottom cell (the gear) opens **Pen Slot Settings**, and the cells in between are your pens: up to nine of them, one per filled slot. The thickness is printed in each cell's corner.

- **Tap a cell** to switch the current pen of the note or document to it. The cell in use is inverted, and it follows along when you change pens in the Supernote's own menu.
- **Tap the cell in use again** to go back to the tool you had before.
- **Fold** turns the bar into one small square; tap it to unfold. A two-finger double tap folds it too. Drag the bar with your finger to move it.

**Pen Slot Settings** (the gear, or a long press on Pen Slots) holds the nine slots. Each slot is a needle pen, ink pen, calligraphy pen, marker, eraser, or lasso, with a color (black, dark gray, light gray, white) and a thickness. Besides the sizes the Supernote offers, the slots go thicker than the built-in pens:

| Pen         | Thickest built-in | Pen Slots go up to         |
| ----------- | ----------------- | -------------------------- |
| Needle pen  | 2.0               | 3.0                        |
| Ink pen     | 2.0               | 5.0                        |
| Calligraphy | 2.0               | 3.0, 4.0, 5.0, 6.0, 7.0, 7.5 |
| Marker      | one size          | 4.0, 5.0, 6.0              |
| Eraser      | 2.5               | 0.7, 1.9, 3.3, 5.0         |

Pen Slots only change which pen you write with; strokes already on the page stay as they are. The pen icon in the Supernote's side bar doesn't follow a pen chosen from the bar.

On the Mosaic whiteboard the bar switches the whiteboard's pen, and its cells are the Ink Palette's nine slots (stored separately from the note and document slots). To restyle strokes you've already written on the whiteboard, use [Ink Palette](#ink-palette-mosaic).

### Smart Lasso

Smart Lasso turns what's on the page into something you can share or reuse. It does one of two things, depending on whether something is selected; its icon shows which.

**With a selection, it shares the content over LocalSend.** Notipal collects the selection and opens the Send panel:

| You selected                                   | What gets sent                                   |
| ---------------------------------------------- | ------------------------------------------------ |
| Handwriting                                    | The text, recognized by the Supernote's handwriting recognition |
| Text boxes                                     | Their text                                       |
| A link to a note or document                   | The linked file itself                           |
| A web link                                     | The address                                      |
| Text in a document (text tool or highlighter)  | The text, as is                                  |
| Strokes or text cards on Mosaic                | The recognized text, or the cards' text          |

Recognized text and text boxes are kept in their order on the page. With a default device set, a single piece of text goes straight to it.

**Without a selection, it captures an area.** Draw around any part of the screen with your pen. A frame appears around it; drag its edges, corners, or move handle to adjust it, and then choose:

- **Send to devices**: send the area as an image over LocalSend.
- **Add to Doc Screenshots**: keep it under Screenshots in [Insert Image](#insert-image), ready to insert into a note.
- **Mosaic card**: turn it into an image card on the Mosaic whiteboard, which opens right away.

This works on any screen: a note, a document, or the whiteboard. The frame closes by itself if you leave it alone for 30 seconds.

Long press Smart Lasso to sync your clipboards to another device. See [Clipboard Sync](#clipboard-sync).

### Document Screenshots

Notipal has three kinds of screenshots, and all of them end up in the same place, ready to insert into a note, send to another device, or put on the Mosaic whiteboard:

- **Page screenshot**: capture the document page and crop it.
- **Long screenshot**: stitch several pages into one tall image or a grid.
- **Area screenshot**: frame any part of the screen with [Smart Lasso](#smart-lasso), in a document, a note, or on the whiteboard.

You capture in a document and insert in a note (or on the whiteboard). Queue several crops while reading, then switch to your note and tap Insert Image once per crop.

**In a document, tap Doc Screenshot** to capture the screen and open the crop editor. Adjust the frame, then pick an action:

| Action                | Result                                                         |
| --------------------- | -------------------------------------------------------------- |
| Insert Next           | Queue the crop. Each tap on Insert Image in a note inserts the next queued crop |
| Add to Queue          | Save the crop to the Screenshots list in Insert Image (last 20 kept) |
| Send to Other Devices | Send the crop over LocalSend                                   |
| Stitch                | Start a long screenshot, or add this page to the one in progress |

Turn on **Multi** to make several crops from the same capture without closing the editor.

**Stitching** joins pages into one image. Capture a page, turn the page, and capture again. Once two pages are in, the stitch editor opens:

- **Long** stacks the pages into one strip. Drag a seam to set the overlap, or tap **Auto Align**.
- **Grid** lays the pages out in columns, ordered by row or by column.
- **Add Page**, **Remove Last**, and **Reverse** change which pages are in it and in what order.

An unfinished stitch is kept for 15 minutes.

**In a note, the same button is Insert Image.** If a crop is queued, a tap inserts it right away. Otherwise the image picker opens. Long press to open the picker at any time.

### Insert Image

Insert Image opens a full-screen picker. The left column chooses the source:

- **Recent**: the newest images from everywhere below, plus the Supernote's own screenshots.
- **Screenshots**: your doc screenshots, queued ones first.
- **From LocalSend**: images sent to this Supernote.
- **Folders**: Document, EXPORT, MyStyle, Note, SCREENSHOT, and INBOX.
- **Trash**, at the bottom.

Tap images to pick them, across sources if you like; the number on each one is the order they go in. Then:

- **Insert** (the arrow) puts them in the note one after another, each one selected so you can place it.
- **Crop & Insert** (one image) trims it and applies a filter: **Original**, **Enhance**, or **B&W Text**, with an ink density from 100% down to 20%.
- **Delete** (the bin) moves them to the Trash, where you can restore them. Trashed images are deleted for good after 30 days.

An image from LocalSend or a queued screenshot is removed once it's inserted. On the Mosaic whiteboard, inserted images become image cards placed side by side in free space.

### Text Receive

Text Receive puts text from another device straight onto the page.

1. Tap **Text Receive** in the side column. A small bubble appears.
2. Drag the bubble to where the text should go.
3. Send text from LocalSend on your phone or computer.

Each message arrives as a text box at the bubble's position. More messages stack below it and skip past anything already on the page. Tap the bubble to place text that's still waiting; long press it to throw that text away.

It works in notes and on Mosaic, where each message becomes a text card.

Where received text goes:

| Situation                                                                 | Text goes to                                                         |
| ------------------------------------------------------------------------- | -------------------------------------------------------------------- |
| A note or the whiteboard on screen, nothing over it, Text Receive on      | The Text Receive bubble                                              |
| The same, Text Receive off                                                | The system clipboard, and Notipal asks whether to turn Text Receive on |
| A document, anywhere outside Notes, or something open over the page: a Supernote menu or dialog, another plugin's window, a Notipal panel or the expanded AI window, a Mosaic menu or the quick switcher, Lexi | The system clipboard |

Text on the system clipboard can be pasted from the keyboard's clipboard into any text field, so text you send while typing somewhere lands where you need it.

The font and size of inserted text boxes are set under **Inserted text boxes** in the settings. Fonts are read from `/sdcard/Document/Fonts/` (`.ttf` or `.otf`).

### Insert Link

An optional note tool. It opens a file browser; tap a document and a link to it is inserted on the page, styled like the Supernote's own links. Use the multi-select button to insert several. Turn it on under **Optional Tools** in the settings.

### Ink Palette (Mosaic)

On the Mosaic whiteboard, Ink Palette in the side column restyles strokes you've already written. Lasso strokes and tap it to change their pen type, color, and thickness in the full panel. Without a selection it shows a small bubble with your nine slots: lasso strokes, then tap a slot to apply it in one step. The changes go into Mosaic's undo history.

In notes and documents, Pen Slots takes this place.

## AI Relay

AI Relay lets you ask an AI about what's on your page and read the answer right there, in notes, documents, and on the Mosaic whiteboard. It's built as a dedicated relay rather than a chat app squeezed onto E Ink, and it's the fastest way to use AI on a Supernote:

- The Supernote only sends the question and draws the answer. The model is called from a phone or a browser next to it, over your own Wi-Fi, with no cloud service of Notipal's in between.
- The connection stays open while you use it, and replies stream in as the model writes them, so the first words show up as soon as the model produces them.
- Questions are built on the page: recognized handwriting, a framed screenshot, or handwritten math go into the input without retyping.
- When you're not using it, the connection rests to save battery and comes back the moment you ask something. A reply that finishes meanwhile is not lost.

**How it got here.** The current design is the result of six months of building and timing the alternatives side by side:

1. **The Supernote calls the model's API itself.** The simplest approach and the slowest: every request and every byte of the reply goes through the tablet's own network stack and processor.
2. **A phone relays the reply as a stream.** Moving the API calls to a phone and streaming the reply back to the Supernote was faster, but still not fast enough.
3. **The relay as it is now.** The Supernote talks directly to RikkaHub's web server (or to a browser page it serves itself) on the local network and follows the conversation's live stream, which is kept open while AI Relay is in use. In repeated comparisons this came out the fastest, and it's what AI Relay uses today.

AI Relay is off by default. Turn it on under **Optional Tools** in the settings, and it appears on the toolbar in notes, documents, and Mosaic. The same settings page can also turn AI off for good: AI Relay, Lexi's Ask AI, and translation all stop, and only uninstalling and reinstalling Notipal brings them back.

### Two Ways to Connect

**Pair with RikkaHub** (recommended). [RikkaHub](https://github.com/rikkahub/rikkahub) is an Android AI chat app that can run a web server. Set up your model and API key in RikkaHub, open **Settings → Web Server**, turn **authentication** off, and start the server. With the phone on the same Wi-Fi, tap **AI Relay** on the Supernote: Notipal finds the phone and asks whether to connect. After that it reconnects by itself, even when the phone's address changes.

- Questions continue the conversation open on the phone, and questions you ask on the phone also arrive on the Supernote.
- Voice questions and recordings use the phone's microphone and RikkaHub's speech recognition.
- Lexi and DocuCraft translations go through the same connection.

**No RikkaHub: open a room from the Supernote.** Switch the backend to **Web page** in AI Relay's settings. The Supernote serves a page and shows a QR code. Scan it with a phone, or type the address into a computer's browser, and that page becomes the AI's side of the relay. Add an API key for your provider in the page (OpenAI, Anthropic, Google Gemini, DeepSeek, OpenRouter, SiliconFlow, Alibaba Bailian, Kimi, Volcengine Ark, xAI, Groq, Mistral, or any OpenAI-compatible service that allows browser calls) and pick a model. The settings are kept on the Supernote, so another browser doesn't need them again.

- The address carries an access code. Anyone with the link can use your API key, so keep it to yourself; **New link** revokes the old one.
- Keep the page open while you use it. A computer browser holds up better than a phone that locks its screen.
- If the paired phone can't be reached when you ask, Notipal offers to switch to the web page, just this once or for good.

### Asking

Type into the input, or add content from the page:

- **Ask from selection**: lasso handwriting and it's recognized into the input. In a document, selected text goes in as is. Switch the mode to **Formula** to attach the selected strokes as a small image for handwritten math; the mode goes back to text after one message.
- **Ask from area**: frame any part of the screen and attach it as an image.
- **Voice** (RikkaHub only): record with the phone; the transcript goes with your question.

Nothing is sent until you tap send, so you can collect several pieces first.

### Replies

Replies show up in the same window with Markdown and LaTeX typeset, and you can keep asking below them. In full screen, **Select** lines and **Insert** them into the note: text becomes text boxes (in the font and size from the settings) and formulas are written as single-line pen strokes. On Mosaic they become text cards, with formulas as strokes on the board. In a document, the selected lines are copied to the system clipboard.

The window can sit over the page as a small window while you write, go full screen, or collapse to a round bubble. The **Inbox** keeps your conversations, recordings, and favorites, with full-text search. AI Relay's own settings (backend, paired phone, how the window opens, translucency) are under **Settings** at the bottom of the inbox.

## Sharing with LocalSend

[LocalSend](https://localsend.org) is a free, open-source app for sharing files and text between nearby devices, on Android, iOS, Windows, macOS, and Linux. Notipal has LocalSend built in on the Supernote side, so transfers go directly between your devices over Wi-Fi.

### Get Connected

1. Install and open LocalSend on your other device.
2. Connect it and your Supernote to the same Wi-Fi network.
3. Open the Notipal toolbar. Its LocalSend service runs while the toolbar is open.

Your Supernote appears as **Supernote** in LocalSend.

### Receiving

| You send                        | It goes to                                                    |
| ------------------------------- | ------------------------------------------------------------- |
| Text                            | The Text Receive bubble, or the system clipboard. See [Text Receive](#text-receive) |
| One image, while a note is open | Notipal asks: **Insert now**, keep it, or reject it           |
| Other images and files          | `/sdcard/INBOX`                                               |
| A plugin package (`.snplg`)     | `/sdcard/MyStyle`                                             |
| A clipboard sync                | Your eight clipboard slots, after you confirm                 |

Received images show up under **From LocalSend** in Insert Image.

### Sending

The Send panel opens from Smart Lasso, from the screenshot crop editor, and from **Share Document**. Check the items to send, then pick a device; **Rescan** looks for devices again. Long press a device to make it your **default**: a single image or piece of text then goes straight to it.

### Share Document

In a document, **Share Document** in the side column sends the file you're reading. If it's a PDF with handwritten notes and the DocuCraft plugin is installed, Notipal has DocuCraft produce a PDF with your annotations and sends that. Otherwise, or if DocuCraft is too old or the export fails, it sends the original file.

### Clipboard Sync

**Sync Clipboard** in the Send panel packs all eight slots and sends them to Notipal on another Supernote, which asks before it accepts. Long pressing Smart Lasso is a shortcut: it syncs to your default device, or opens the Send panel ready to sync.

## Settings

Open them from the plugin's settings button in the Supernote plugin manager.

- **Toolbar**: reorder the shared tools (long press and drag); show or hide the optional tools (AI Relay, Insert Link); turn AI off for good.
- **Connections**: the default LocalSend device; whether screenshots are sent from a sharper 600 DPI render; font and size of inserted text boxes.
- **About**: version and feedback.

Closing the settings also closes the toolbar.

## Mosaic

Mosaic is a handwriting whiteboard for Supernote. You can write anywhere, make cards, move and resize them, connect them, and keep several boards. It is developed alongside Notipal and works best with it installed: Notipal brings pages, images, text, and clipboards into Mosaic, and almost all of Notipal's tools work on the whiteboard. Mosaic runs on its own too.

Installing Mosaic also gives the Supernote's own Notes and Documents two things they don't have: [Quick Switch](#quick-switch) and [Sketch](#sketch).

Open the whiteboard from the **Mosaic** button in the Note or Document toolbar, or from the quick switcher.

### Quick Switch

Once Mosaic is installed, you can swipe up from the bottom edge of any note or document page to jump to another note, document, or board. It works in the Supernote's built-in Notes and Documents apps; Mosaic doesn't have to be open, and you don't go back to the file list. It works on the whiteboard too, including inside a Note Card.

Swipe up with one finger, starting away from the left and right edges, and keep the pen away from the screen while you swipe. The switcher slides up from the bottom.

The switcher has two tabs, **Recent** and **Sketch**, and opens on the one you used last.

**On a note or document**, Recent shows your boards and recently opened notes and documents together, most recent first:

- Board cards show the board as you left it and how many cards it has.
- File cards show the cover and where you are, like "Page 3 / 12". Encrypted files show a lock.
- Tap a board to open Mosaic on it. Tap a file to open it at the page you were on.
- **+ New board** creates an untitled board with the current template and opens it.

**On the whiteboard**, it shows all your boards plus **Previous note** and **Previous document**. Tap a board to switch to it, tap one of the other two to go back to that file, or tap **Recent files ↗** to close Mosaic and see the recent file list.

Long press a card to select cards. **Delete** hides files from the list (they come back the next time you open them) and deletes boards after asking. The main board can't be deleted.

Opening the switcher saves the current board first. Tap outside it to close it.

### Sketch

Sketch is a handwriting area inside the quick switcher that works like a sticky note. Swipe up from any note, document, or the whiteboard, write down a phone number, a reminder, or a quick idea, and tap outside to go back to what you were doing. It's there the next time you swipe up, and the switcher reopens on the Sketch tab if that's where you left it.

- Its toolbar has three pen slots (calligraphy pen, fineliner, or pencil), the eraser, and the lasso. Tap a selected slot again to choose the pen and width. These slots are separate from the whiteboard's.
- Two-finger tap to undo, three-finger tap to redo.
- What you write is saved automatically and kept in the Card Box as **Sketch**.

The last cell belongs to Notipal. If Notipal is installed but its toolbar isn't open, it shows the Notipal icon and opens it. With the toolbar open, it becomes the clipboard: with a lasso selection, the selection is saved to the slot you pick; without one, the slot you pick is pasted into the sketch. Sketch handwriting is the same size as on a note page, so it pastes into a note at the size you wrote it.

**Sketch and the whiteboard.** Put a card in the [Card Box](#boards-card-box-and-backup), select it there, and tap **Paste to sketch**: its handwriting is copied into the sketch, and the card stays in the box. Now that card is one swipe away from any note or document, which is handy for keeping a formula, an outline, or a to-do list in view while you work in another file. Going the other way, select **Sketch** in the Card Box and tap **Paste to board** to turn what you jotted down into a text card on the whiteboard.

### Notipal on the Whiteboard

Mosaic and Notipal are made to be used together. With both installed, start Notipal and open Mosaic: the toolbar switches to its Mosaic set, both on the whiteboard and inside Note Cards.

- **Shared clipboards.** The eight slots are the same in notes, documents, on the whiteboard, and in Sketch. Lasso handwriting in a note and paste it on the board, copy strokes from the board back into a note, or carry a few lines from Sketch into a document. Text boxes and images from a note become text cards and image cards. Cards themselves can't be saved, but the handwriting on them is.
- **Insert Image**, queued doc screenshots, and **Smart Lasso → Mosaic card** arrive as image cards in free space.
- **Text Receive** and **AI Relay** replies arrive as text cards below the bubble, with formulas as strokes.
- **Pen Slots** switches the whiteboard pen; **Ink Palette** restyles the strokes you lasso.
- **Smart Lasso** recognizes and sends lassoed strokes, or captures any area of the screen.
- **Import notes and documents.** **Note to Mosaic** and **Doc to Mosaic** (in the side column of a note or PDF document, while Mosaic is closed) import the pages you pick. Note pages become one Note Card, with handwriting as strokes and text boxes as text. PDF pages are rendered at 600 DPI into a Document Card, with handwritten notes, highlights, and underlines kept editable. Because of a Supernote limitation, the last page can't be read while it's on screen; turn to another page first. You can also start a whole new board from a note or PDF, one card per page.

Notipal stays out of Mosaic's way: if its toolbar covers Mosaic's toolbar or close button, it moves to a free spot, and it hides while a Mosaic menu, pen panel, or the quick switcher is open.

### Read and Write on Top (Nomad and Manta)

These features are exclusive to the Supernote Nomad and Manta. They let you keep Mosaic open over a document while you read it, and take notes on top without leaving the page.

**Translucency.** Turn on **Board translucency** in the side menu and the document or note underneath shows through the board; the slider sets how much (10% to 80%). Inside a Note Card your handwriting gets a white backing so it stays readable over the page below. The board turns opaque for a moment while you drag cards, and below 60% zoom.

**Slider page turn.** With translucency on, turn on **Slider page turn** under it. Swiping up on the side slider then turns the document underneath to the next page, and swiping down goes back, on the whiteboard and inside Note Cards. Mosaic stays open the whole time. While it's on, the slider turns pages instead of undoing.

**Page turn mode.** Also in the side menu. Two-finger gestures stop scrolling the view continuously and move it in whole steps instead: a two-finger swipe moves the view by five sixths of a screen in that direction, and a pinch zooms in one step when you let go. That means fewer partial refreshes on E Ink, much like turning a page. It works on the whiteboard and inside Note Cards.

**Recommended: take notes in a Note Card.** Open a document, open Mosaic over it, and turn on translucency and slider page turn. Draw a rectangle to make a card, turn it into a Note Card, and open it. Inside, the note is one long vertical page: write down the page, scroll on as far as you need, and turn the document underneath with the slider whenever you're ready for the next page. Reading and writing in one place, with the endless scrolling page of a reMarkable.

### Whiteboard

**Toolbar.** A small capsule with More, four pen slots, the eraser, and the lasso; the close button sits alone at the top right. Drag the capsule anywhere; long press More to switch it between horizontal and vertical; double tap with two fingers to hide it. Tap More or swipe in from the left edge for the side menu: zoom, undo and redo, boards, Card Box, backup, templates, translucency, and page turn mode.

**Pens.** Each slot holds a calligraphy pen (slanted nib, 8 widths), a fineliner (12 widths), a marker (black, dark gray, light gray, or underline), or a pencil (HB to 8B, with paper grain and tilt). Tap a selected slot again to open its pen panel. Notipal's Pen Slots bar can switch the whiteboard pen as well, including the thicker sizes.

**Writing.** The canvas, every card, and the connection between two cards are separate writing surfaces: strokes on a card move with it. Ink turns white over dark areas. Undo and redo with the side slider. Pinch to zoom (10% to 400% from the menu) and drag with two fingers; a palm resting near the pen doesn't move the board.

**Shapes.** There is no shape tool. Pause the pen for half a second at the end of a stroke: a line, triangle, or ellipse becomes a clean shape. Keep holding on a line to adjust its angle and length with snapping, and tap with a finger to start the next segment; closing the path makes a triangle, rectangle, or polygon. Selected shapes can be made square, golden, A-paper, circular, isosceles, equilateral, right-angled, filled, or hollow.

**Cards.**

- Draw a rough rectangle and pause to make a card from the strokes inside it.
- Start a stroke in a card and end it on empty canvas to pull out a new connected card.
- Draw a bracket on a card's edge to widen or lengthen it.
- Lasso handwriting and tap **Recognize** to turn it into a text card.
- Hold the pen still on a text card to edit its text with the keyboard (headings, bold, italic, indents, size).
- Long press a card with your finger to pick it up and move it. Lasso buttons copy, recolor (white or black), turn a text card into a Note Card, put a card in the Card Box, or delete.

On cards, the marker works like a text scanner: it only highlights or underlines the typed text it passes over, snapped to the characters.

**Connections.** Drag a card until it touches another card of the same color, or pull a new card out of one, to join them with a bridge that bends as they move. You can write on a bridge; that locks the two cards into a group. Draw from one card to another to add a thin thread instead.

**Note Cards** hold a long vertical note page. Tap one, or hold the pen on it, to open it; write as far down as you like, and give it a handwritten title at the top. **Document Cards** are PDF pages imported from Notipal, with their handwritten notes and highlights as editable strokes. **Image cards** come from Notipal's images, screenshots, and Smart Lasso.

**Translucency and page turning.** See [Read and Write on Top](#read-and-write-on-top-nomad-and-manta).

**Insert into a note.** When Mosaic is opened from a note, lasso an area and tap **Insert into note**: it goes into the note as an image with a Mosaic tag. Tap the tag later to jump back to that spot on the board; the image updates when you close Mosaic.

### Boards, Card Box, and Backup

- **Boards.** Keep as many boards as you like. Create one from the side menu (blank, from a note, or from a document) or with **+ New board** in the quick switcher. Each board keeps its own cards, template, translucency, and view. Switch and delete boards in the quick switcher.
- **New board from a note or document.** Notipal lets you pick the file and pages, and each page becomes a Note Card or Document Card on the new board. Documents must be PDF; for other formats, Notipal offers to convert them with DocuCraft.
- **Card Box.** Move cards between boards: select one card and tap the box button to put it in, then open **Card Box** from the side menu on another board and **Paste to board**. The sketch lives here too.
- **Saving.** The board saves when you close it, pull down from the top, open the quick switcher, or switch boards. **Back up** in the side menu writes the board to `/sdcard/EXPORT/Notipal/mosaic/`, and **Import board** restores a backup as a new board or over the current one.

### In Development: Inkflow and Web Sync

The features in this section are an introduction to work in progress. Their first versions are built and are still being tested and tuned, and they are not fully available in release builds yet.

**Web sync.** Mosaic can connect to a companion web whiteboard: over your local network by entering the computer's address in the side menu, or through an online room you share as a link. Strokes, cards, and connections sync in real time, and handwriting appears on the web page from the first point while you're still writing. When both sides change the board at once, Mosaic merges the changes card by card and stroke by stroke. The web whiteboard has the same pens, eraser, lasso, and pen gestures (make a card, grow one with a bracket, pull out a new card, draw a thread, pause to make a shape).

**Inkflow (drawing tablet mode).** Inkflow turns the Supernote into a pressure- and tilt-sensitive pen tablet for the web whiteboard on your computer. Select text in a document and tap **Inkflow** in the selection toolbar: the text becomes a text card on the computer's whiteboard, and a window opens over the lower part of the document. The window maps to the whole computer screen, and the Supernote connects over USB as a pen tablet, so the cursor follows the pen while it hovers.

- A small toolbar over the window has the pen (calligraphy, fineliner, or marker), eraser, lasso, and move; the tool and pen stay in step with the web page.
- Two fingers pan and zoom the computer's whiteboard. One finger selects, moves, and resizes cards on the computer.
- Your palm can rest on the screen while you write. The side slider undoes and redoes, or turns the document page underneath in page turn mode.
- Inkflow needs web sync connected with the whiteboard open in the computer's browser, a USB cable to the computer, and ADB debugging turned off. Tap outside the window or pull down from the top to close it.

## Build from Source

You need Node.js, a JDK, and the Android SDK.

```sh
npm install
./buildPlugin.sh               # build/outputs/<name>.snplg
./buildPlugin.sh --with-logs   # a -dev package that writes logs to logcat
```

On Windows use `buildPlugin.ps1` (`-WithLogs` for the dev package). Each build bumps the version in `PluginConfig.json`.

Notipal and Mosaic are not official plugins. Supernote and Ratta are not responsible for anything that happens while you use them.