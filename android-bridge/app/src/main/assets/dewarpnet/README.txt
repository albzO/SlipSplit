DewarpNet LiteRT community conversion (CPU trial)
Upstream: https://github.com/cvlab-stonybrook/DewarpNet
Export: https://huggingface.co/litert-community/DewarpNet-LiteRT
Revision: dfc68e3e462a6eb7eb8c4dbbff4049e3bd3cc9e3
dewarp.tflite SHA256: 0f72fc016f50bd16cc2055e0b499a4317702ed81b3ff6672fd9affe0bad6d064
Download dewarp.tflite from the pinned Hugging Face revision and check its SHA256.
Input: float32 BGR NCHW [1,3,256,256], /255
Output: float32 planar XY backward map [1,2,128,128], normalized [-1,1]
Postprocess: 3x3 blur (reflect101), bilinear resize, bilinear sampling with
align_corners=true and zero padding. Output retains the original dimensions.
No runtime download or cloud processing. MIT license in LICENSE.txt.
