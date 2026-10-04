"""Text width measurement, used by the plan stage to decide overflow handling before
any PDF is touched. reportlab ships real AFM metrics for the standard 14 fonts, so unlike
the Java side (which approximates Helvetica width per-character), this can measure exactly -
but the result is used for the same purpose: decide shrink/truncate/error at plan time.
"""
from reportlab.pdfbase.pdfmetrics import stringWidth

FONT_NAME = "Helvetica"


def width_of(text, font_size):
    return stringWidth(text, FONT_NAME, font_size)
