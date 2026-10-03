from io import BytesIO

from PIL import Image, ImageOps, UnidentifiedImageError

from app.errors import ServiceError

ALLOWED_FORMATS = {"JPEG", "PNG", "WEBP"}
MAX_SIDE = 1536  # fewer image tiles = faster OCR; still sharp enough for labels


def prepare_image(data: bytes) -> Image.Image:
    """Verify the bytes are a real JPG/PNG/WEBP, fix rotation, shrink if huge."""
    try:
        image = Image.open(BytesIO(data))
        if image.format not in ALLOWED_FORMATS:
            raise ServiceError("Please upload a JPG, PNG or WEBP image.", 400)
        image.load()
    except ServiceError:
        raise
    except (UnidentifiedImageError, OSError, Image.DecompressionBombError):
        raise ServiceError("That file is not a valid image.", 400)

    image = ImageOps.exif_transpose(image)  # phone photos store rotation in EXIF
    if image.mode not in ("RGB", "L"):
        image = image.convert("RGB")
    image.thumbnail((MAX_SIDE, MAX_SIDE))
    return image
