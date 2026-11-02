"""Download the pinned MIT-licensed community DewarpNet LiteRT export."""
import hashlib
import urllib.request
from pathlib import Path

REPO = "litert-community/DewarpNet-LiteRT"
REVISION = "dfc68e3e462a6eb7eb8c4dbbff4049e3bd3cc9e3"
SHA256 = "0f72fc016f50bd16cc2055e0b499a4317702ed81b3ff6672fd9affe0bad6d064"
TARGET = Path(__file__).resolve().parents[1] / "android-bridge/app/src/main/assets/dewarpnet/dewarp.tflite"


def main():
    if TARGET.exists():
        with TARGET.open("rb") as existing:
            if hashlib.file_digest(existing, "sha256").hexdigest() == SHA256:
                print(f"Already verified: {TARGET}")
                return
    TARGET.parent.mkdir(parents=True, exist_ok=True)
    partial = TARGET.with_suffix(".download")
    try:
        urllib.request.urlretrieve(f"https://huggingface.co/{REPO}/resolve/{REVISION}/dewarp.tflite", partial)
        with partial.open("rb") as downloaded:
            actual = hashlib.file_digest(downloaded, "sha256").hexdigest()
        if actual != SHA256:
            raise ValueError(f"SHA256 mismatch: {actual} != {SHA256}")
        partial.replace(TARGET)
        print(f"Downloaded {TARGET.stat().st_size} bytes; SHA256 {actual}")
    finally:
        partial.unlink(missing_ok=True)


if __name__ == "__main__":
    main()
