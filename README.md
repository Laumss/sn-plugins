# Notipal

Notipal is a toolkit plugin for **Supernote tablets**.

It adds a floating toolbar to Notes and Documents. From there you can take document screenshots, insert images, keep things on eight clipboards, restyle handwriting, send content to other devices with LocalSend, and ask an AI on your phone about what you wrote. It also works together with [Mosaic](https://github.com/Not-Just-Supernote/sn-plugin-mosaic), a whiteboard plugin for Supernote.

Everything happens on your local network. Notipal has no cloud service of its own.

## Quick Start

### Get the App

Download the latest `.snplg` from [GitHub Releases](https://github.com/Not-Just-Supernote/sn-plugin-notipal/releases), or [build it from source](#build-from-source).

### What You Need

| For                                               | You need                                                     |
| ------------------------------------------------- | ------------------------------------------------------------ |
| The toolbar, screenshots, clipboards, Ink Palette | A Supernote with plugin support                              |
| Sending and receiving                             | Wi-Fi, and [LocalSend](https://localsend.org) on the other device |
| AI Relay                                          | An Android phone running RikkaHub on the same Wi-Fi          |
| Mosaic features                                   | The [Mosaic](https://github.com/Not-Just-Supernote/sn-plugin-mosaic) plugin |

### First Launch

Notipal has three entry points:

- **Note toolbar**: the Notipal button opens or closes the toolbar in Notes.
- **Document toolbar**: the same button opens or closes the toolbar in Documents.
- **Document text menu**: after you select text in a document, **Send to other devices** sends that text over LocalSend.

The first time you open it, Notipal asks for file read, write, and delete access, and for network access. Choose **Always allow** for every one. Notipal runs in the background, so a one-time grant stops working.

## Features

### Floating Toolbar

The toolbar has a column of tools and a narrow side column with the clipboards and a few utility buttons. Drag it with your finger to move it anywhere on the screen. The pen can tap its buttons, and it won't leave ink on the page underneath.

Which tools appear depends on where you are:

| Where    | Tools                                                        | Side column                                        |
| -------- | ------------------------------------------------------------ | -------------------------------------------------- |
| Note     | Clipboard Manager, Insert Image, Smart Lasso, Ink Palette, AI Relay | Clipboards, Text Receive, Note to Mosaic, Collapse |
| Document | Doc Screenshot, Smart Lasso, Ink Palette, AI Relay           | Clipboards, Go to Note, Doc to Mosaic, Collapse    |
| Mosaic   | Insert Image, Smart Lasso, AI Relay                          | Clipboards, Text Receive, Ink Palette, Collapse    |

Insert Image and Doc Screenshot are one button: it inserts images in a note and captures the screen in a document.

**Collapse** docks the toolbar to the nearest screen edge as a small handle. Double tap a note or document with two fingers to do the same; nothing happens when the toolbar is already collapsed. Tap the handle, or swipe it toward the middle of the screen, to bring the toolbar back. Long press the handle to close Notipal completely.

**Go to Note** switches from a document straight back to the Notes app.

**Note to Mosaic** and **Doc to Mosaic** import pages into Mosaic. See [Use with Mosaic](#use-with-mosaic).

### Clipboards

Notipal has eight clipboard slots. They are numbered in the side column, and a filled slot shows white on black. When not all eight fit, swipe up or down on the numbers to see the rest.

| Action                   | With a lasso selection                                  | Without a selection |
| ------------------------ | ------------------------------------------------------- | ------------------- |
| Tap a slot               | Save the selection into it, asking before it overwrites | Paste it            |
| Long press an empty slot | Cut: save the selection, then delete it from the page   | —                   |
| Long press a filled slot | Clear it                                                | Clear it            |

A paste lands in the same spot on the page where the content was copied, and comes back already lasso-selected so you can move it right away.

Clipboards hold handwriting and shapes. Titles, images, links, and text boxes can't be saved. If a selection mixes them, Notipal skips what it can't save, saves the rest, and tells you what it skipped.

**Clipboard Manager** is a note tool that opens the slots with their previews. With a lasso selection, the slot you tap saves it. Without one, the slot you tap is pasted. Turn it on under Note-only Tools in the settings.

The same eight slots are shared with Mosaic, and you can copy them all to another Supernote. See [Clipboard Sync](#clipboard-sync).

### Smart Lasso

Smart Lasso works in two ways, depending on whether something is already selected.

The button's icon shows what a tap will do. With nothing selected it shows a pen drawing a lasso. While handwriting is selected it shows a pen inside a lasso. It switches to a lassoed T with an arrow while the selection holds only text boxes or links, or while text is selected in a document with the text selection tool or the highlighter.

**With a note lasso selection.** Notipal collects the selection and opens the Send panel. Handwriting is recognized as text, and text boxes, images, and links come along too.

**Without a selection.** Draw around any area of the screen with your pen. A frame appears around it. Drag its edges, corners, or move handle to adjust it, and then choose what to do with it:

- **Send to devices**: send the area as an image over LocalSend.
- **Add to Doc Screenshots**: save it to the screenshot history.
- **Mosaic card**: turn it into an image card in Mosaic. Mosaic opens right away. This button is hidden while the Mosaic whiteboard is open.

The frame closes by itself if you leave it alone for 30 seconds.

Long press Smart Lasso to sync your clipboards to another device. See [Clipboard Sync](#clipboard-sync).

### Document Screenshots

Doc Screenshot behaves differently in Documents and in Notes, so you capture in one and insert in the other.

**In a Document, tap Doc Screenshot** to capture the screen and open the crop editor. Adjust the frame, choose a filter, and then pick an action:

| Action                | Result                                                       |
| --------------------- | ------------------------------------------------------------ |
| Insert Next           | Queue the crop as the next screenshot to insert into a note  |
| Add to Queue          | Save the crop to the screenshot history                      |
| Send to Other Devices | Send the crop over LocalSend                                 |
| Stitch                | Start a long screenshot, or add this page to one already in progress |

Turn on **Multi** to make several crops from the same capture without closing the editor.

The filters are **Original**, **Enhance**, and **B&W Text**, with an ink density from 100% down to 20%. They're designed for an E Ink screen.

**Stitching** joins pages into one image. Capture a page, turn the page, and stitch again. Once two or more pages are in, the stitch editor opens:

- **Long** stacks the pages into one strip. Drag a seam to set the overlap, or tap **Auto Align** to match the pages for you.
- **Grid** lays the pages out in columns, ordered by row or by column.
- **Add Page**, **Remove Last**, and **Reverse** change which pages are in it and in what order.

A stitch you leave unfinished is kept for 15 minutes.

**In a Note, the same button is Insert Image.** If you chose Insert Next in a document, a tap inserts that screenshot right away. Otherwise the image picker opens. Long press it to open the picker at any time. Your crops are under **Screenshots** in the picker: the queued ones first, then your last 20 saved crops.

### Insert Image

Insert Image opens a full-screen picker. The column on the left chooses where the images come from:

- **Recent**: the newest images from all the places below, and the Supernote's own screenshots.
- **Screenshots**: your doc screenshots.
- **From LocalSend**: images sent to this Supernote over LocalSend.
- **Folders**: Document, EXPORT, MyStyle, Note, SCREENSHOT, and INBOX. Tap a folder in the grid to open it; inside it, the back arrow at the top left goes up a folder.

The images are in a grid; swipe up or down to turn a screen. A thin black bar at the right shows where you are. Under each image are its name and its date and size. Tap images to pick them, across sources if you like. Each picked image shows its number, and that's the order they go in. Then use the icons at the top right. While images are picked, the cross at the top left turns into a back arrow that drops the picks.

- **Insert** (the arrow) puts them in the note one after the other.
- **Crop & Insert** (the crop marks, one image) trims it and applies the same filters as Doc Screenshot.
- **Delete** (the bin) moves them to the **Trash**.

An image from LocalSend is deleted once it's in the note, and a queued screenshot leaves the queue.

**Trash** is at the bottom of the left column. There you can **Restore** picked images to where they were, or delete them for good (the bin, tap twice). With nothing picked, **Empty trash** at the top right deletes everything in it (tap twice). Images are deleted for good after 30 days in the trash. The trash is kept in Notipal's own plugin folder (`image-trash`), not on the shared storage.

When Mosaic is open, on the whiteboard or inside a Note Card, inserted images become Mosaic image cards. Each one goes into free space on the screen, so several picked images sit side by side.

### Text Receive

Text Receive puts text from another device straight onto the page you're writing on.

1. Tap **Text Receive** in the side column. A small bubble appears.
2. Drag the bubble to where the text should go.
3. Send text from LocalSend on your phone or computer.

Each message arrives as a text box at the bubble's position. More messages stack below it and skip past anything already on the page. Tap the bubble to place text that's still waiting. Long press the bubble to throw that text away.

Text Receive works in Notes and in Mosaic, where each message becomes a text card. It stops automatically when you switch notes, change the page structure, or open a document. If text arrives while Text Receive is off, the sender's transfer is declined and Notipal reminds you to turn it on.

### Ink Palette

Ink Palette restyles handwriting you've already written.

**Pen slots.** Save up to nine pens. Each slot has a pen type, a color, and a thickness:

| Setting   | Options                                      |
| --------- | -------------------------------------------- |
| Pen type  | Needle, Ink Pen, Brush, Marker               |
| Color     | Black, Dark Gray, Light Gray, White          |
| Thickness | Thin, Medium, Thick, and the pen's own sizes |

A marker's color is set by which marker you choose. Lock a slot to keep it when you reset the rest.

**Restyle.** Lasso some strokes and tap Ink Palette to edit them in the full panel. Without a selection, tap Ink Palette to show a small slot bubble instead. Lasso strokes, then tap a slot on the bubble to apply that pen in one step. Long press Ink Palette to open the full panel at any time.

Only handwriting can be restyled. If the selection includes titles, links, text boxes, or images, Notipal won't change it.

**Restore points.** Before every restyle, Notipal saves a snapshot of the page. You can also tap **Create** to save one yourself. The latest three are kept. Tap one to paste it back onto the page.

In Mosaic, on the whiteboard or inside a Note Card, Ink Palette restyles Mosaic strokes.

### AI Relay

AI Relay connects your Supernote to **RikkaHub** running on an Android phone. The AI runs on the phone. The Supernote sends the question and shows the reply.

**Pairing.** In RikkaHub, turn on the web server and turn **off** its authentication. With both devices on the same Wi-Fi, tap **AI Relay** on the toolbar. When Notipal finds the phone, it asks whether to connect. After that, it reconnects to the same phone by itself.

**Asking.** Turning on AI Relay opens the AI bubble, which has these actions:

- **Ask from selection** has two modes:
  - **Text query**: recognizes the handwriting you lasso and sends it as a question.
  - **Formula**: sends your handwritten math as an image. The reply has a transcription in LaTeX and a short solution.

  If nothing is selected, circle the content with the pen first.
- **Ask from image**: draw a frame around any part of the screen and send it as an image.
- **Conversations**: open the AI Relay inbox.

If the phone can't be reached, the question isn't sent: Notipal says so (and offers the web page), and your text stays in the input so you can try again. A reply that doesn't arrive within 90 seconds is reported as a timeout.

AI Relay's own settings are under **Settings** at the bottom of the inbox's left column, not in the plugin settings (opening those restarts the plugin): the AI backend and the web page's link, the paired phone, how the window opens, opening on a reply, the phone following, translucency. The same page shows the connection (connected, retrying in so many seconds, resting) and has **Search again**.

AI Relay stays on while its window or bubble is up, and keeps the battery in mind: when the phone can't be reached it retries less and less often (up to every two minutes), and connects right away when the phone shows up again or you ask something. With the screen off, or the window collapsed to its bubble or put away for three minutes, the connections rest until you use it again. Nothing in flight is interrupted, and a reply the phone finishes meanwhile still arrives as a card when the connections come back.

**Replies.** The newest replies appear as cards under the bubble. Tap a card to read it with Markdown and formulas rendered. Then you can:

- **Select** some lines and **Insert** them into the note.
- **Replace handwriting**: for a formula reply, swap your handwritten math for typeset formulas.
- **Delete** the reply.

In the inbox, tap **Edit** to select several messages. Then **Re-add** puts them back on the bubble and **Delete** removes them.

In Documents, AI Relay can answer questions but can't insert into the page, and Formula mode isn't available.

## Sharing with LocalSend

[LocalSend](https://localsend.org) is a free, open-source app for sharing files and text between nearby devices. It works on Android, iPhone, iPad, Windows, macOS, and Linux. Transfers go directly from one device to another over your local network.

Notipal has LocalSend built in on the Supernote side. Install LocalSend on your phone, tablet, or computer, and you can send text, images, documents, and links to your Supernote, or send content from your Supernote back to those devices.

### Get Connected

1. Download and open [LocalSend](https://localsend.org) on your other device.
2. Connect it and your Supernote to the same Wi-Fi network.
3. Open the Notipal toolbar. Its LocalSend service starts automatically while the toolbar is open and Wi-Fi is connected.

Your Supernote appears as **Supernote** in the LocalSend app. Select some files or text there, then tap **Supernote** to send them. To send something the other way, keep LocalSend open on the receiving device and choose it in Notipal's Send panel.

### Receiving

What you send decides where it goes:

| You send                        | It goes to                                                   |
| ------------------------------- | ------------------------------------------------------------ |
| Text                            | The Text Receive bubble, if it's on                          |
| One image, while a note is open | Notipal asks: **Insert now**, keep it in the inbox, or reject it |
| Other images and files          | `/sdcard/INBOX`                                              |
| A plugin package (`.snplg`)     | `/sdcard/MyStyle`                                            |
| A clipboard sync                | Your eight clipboard slots, after you confirm                |

Received images show up under **From LocalSend** in Insert Image.

### Sending

The Send panel opens from Smart Lasso, from the Doc Screenshot editor, and from **Send to other devices** in a document's text menu.

Check the items you want to send, which can be text, images, or links. Then pick a device. Notipal scans the network for devices with LocalSend open, and **Rescan** looks again.

Long press a device to make it your **default**. After that, a single image or a single piece of text goes straight to it without opening the panel.

### Clipboard Sync

**Sync Clipboard** packs all eight clipboard slots and sends them to Notipal on another Supernote. The other device asks before it accepts.

Long pressing Smart Lasso is a shortcut. It syncs to your default device, or opens the Send panel ready to sync if you haven't set one.

## Use with Mosaic

[Mosaic](https://github.com/Not-Just-Supernote/sn-plugin-mosaic) is a whiteboard plugin for Supernote. Install it next to Notipal and the two work together.

### Import Note Pages

In a note, tap **Note to Mosaic** in the side column. A page picker opens with the current page already checked. Pick the pages you want and tap **Confirm**.

Notipal converts the pages with a progress panel that you can cancel. When it finishes, confirm the dialog and Mosaic opens with a new Note Card. The card is titled with the note name, and a second line lists the page numbers if you picked only some of them.

Handwriting and shapes come along as strokes, text boxes and excerpts as text, and images, title backgrounds, and links as pictures. Stars are skipped.

### Import Document Pages

In a PDF document, tap **Doc to Mosaic** in the side column. The same page picker opens:

- Thumbnails are laid out five by four. Swipe left or right, or use the bar at the bottom, to see more.
- Tap a thumbnail to check or uncheck it. Long press it to zoom in.
- **Select all** checks every page, and the counter shows how many are checked.
- The current page is marked and checked at first.

After you confirm, Notipal renders each page at 600 DPI. When it's done, confirm the dialog and Mosaic opens with a Document card: the pages stacked in a column, titled with the document name and the page numbers.

| From the page                        | In Mosaic                                             |
| ------------------------------------ | ----------------------------------------------------- |
| Text, pictures, other annotations    | Part of the page image                                |
| Handwritten notes                    | Strokes on the card that you can erase or lasso       |
| Text highlights and underlines       | Highlights and underlines on the card                 |

Only PDF documents can be imported. Because of a Supernote limitation, Notipal can't read the last page while it's the page on screen. Turn to another page and try again.

Mosaic has to be closed for either import, and only one import runs at a time. If Mosaic isn't installed or doesn't have file access, Notipal tells you.

### More Ways to Send to Mosaic

- **Smart Lasso → Mosaic card**: any area of the screen becomes an image card in Mosaic.
- **Mosaic mode**: while Mosaic is open, on the whiteboard or inside a Note Card, the toolbar switches to the Mosaic tool set.
  - Images and doc screenshots arrive as image cards.
  - Received text becomes text cards. They're placed where the Text Receive bubble is when you tap it, and move down past any card or handwriting already there.
  - AI Relay replies can be placed as text cards.
  - Ink Palette restyles Mosaic strokes.
- **Shared clipboards**: the eight slots work on the whiteboard and inside Note Cards too. Copy strokes in a note and paste them in Mosaic, or the other way around. Mosaic cards can't be saved, but the handwriting on them is, and Notipal tells you it skipped the cards. Cutting removes only the saved handwriting; the cards stay.

### Sharing the Screen

Notipal stays out of Mosaic's way:

- If the Notipal toolbar covers Mosaic's toolbar or close button, it moves to the nearest free spot. Its saved position doesn't change.
- When a Mosaic menu or pen panel opens, Notipal hides its toolbar and bubbles, and brings them back when the menu closes.
- While Mosaic's drawing tablet is open over a document, Notipal hides everything, and comes back when the tablet closes.

Notipal is not an official plugin. Supernote and Ratta are not responsible for anything that happens while you use it.
