"""The "draw" half of the pipeline (docs/SPEC.md section 6, step 6). Builds one reportlab
overlay canvas per page, then merges each overlay onto the matching page of the source PDF
with pypdf. Knows nothing about JSON/data-binding/formatting - only geometry and text,
mirroring the separation PdfDrawer.java keeps.
"""
import io

from pypdf import PdfReader, PdfWriter
from reportlab.pdfgen import canvas

from text_measure import FONT_NAME, width_of


def draw(pdf_path, draws, out_path):
    reader = PdfReader(pdf_path)
    num_pages = len(reader.pages)

    # Group draws by 1-based page number so each page gets exactly one overlay canvas.
    by_page = {}
    for d in draws:
        by_page.setdefault(d["box"]["page"], []).append(d)

    overlay_readers = {}
    for page_num, page_draws in by_page.items():
        page = reader.pages[page_num - 1]
        page_height = float(page.mediabox.height)

        buf = io.BytesIO()
        c = canvas.Canvas(buf, pagesize=(float(page.mediabox.width), page_height))
        for d in page_draws:
            box = d["box"]
            # Coordinate-system conversion: annotation boxes are top-left-origin
            # (docs/SPEC.md section 2); reportlab's canvas is bottom-left-origin, same
            # flip PdfDrawer.java performs: pdfY = pageHeight - box.y - box.height.
            pdf_x = box["x"]
            pdf_y = page_height - box["y"] - box["height"]

            text = d["text"]
            if not text:
                continue
            font_size = d["font_size"]
            text_width = width_of(text, font_size)
            align = d["align"]
            if align == "left":
                draw_x = pdf_x
            elif align == "center":
                draw_x = pdf_x + (box["width"] - text_width) / 2
            else:
                draw_x = pdf_x + box["width"] - text_width
            # Same baseline-centering nudge as PdfDrawer.java: center by box height, then
            # nudge up ~20% of font size to approximate descender height.
            draw_y = pdf_y + (box["height"] - font_size) / 2 + font_size * 0.2

            c.setFont(FONT_NAME, font_size)
            c.setFillColorRGB(0, 0, 0)
            c.drawString(draw_x, draw_y, text)
        c.save()
        buf.seek(0)
        overlay_readers[page_num] = PdfReader(buf)

    writer = PdfWriter()
    for i in range(num_pages):
        page = reader.pages[i]
        page_num = i + 1
        if page_num in overlay_readers:
            page.merge_page(overlay_readers[page_num].pages[0])
        writer.add_page(page)

    with open(out_path, "wb") as f:
        writer.write(f)
