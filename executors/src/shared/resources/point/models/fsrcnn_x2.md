# Модель увеличения снимка (fsrcnn_x2.onnx)

`fsrcnn_x2.onnx` — FSRCNN ×2 (Dong et al., «Accelerating the Super-Resolution Convolutional Neural
Network»), веса из проекта https://github.com/Saafke/FSRCNN_Tensorflow (GSoC 2019 для OpenCV,
лицензия Apache-2.0): `models/FSRCNN_x2.pb`, sha256
`366b33f0084c7b3f2bf6724f0a2c77bca94fcec9d7b6d72389d330073b380d5c`.

Из `.pb` в ONNX переложено без переобучения: восемь свёрток, PReLU между ними (наклон
`alpha · 2 · y` — так он записан в графе TensorFlow), смещение последнего слоя — в его свёртке.
Последний шаг графа, DepthToSpace, в модель не вошёл: импортёр OpenCV 4.9 не собирает его без
известного заранее размера входа, поэтому перестановку делает код (`depthToSpace`). Сверено с
исходным графом: расхождение — в пределах округления float.

Вход — `[1, 1, h, w]` float32, яркость Y (YCrCb) / 255, размер любой. Выход — `[1, 4, h, w]`:
четыре канала, которые раскладываются в картинку `2h × 2w` (канал `2i + j` → точка `(2y + i, 2x + j)`).
35 КБ.

Чем Point её зовёт: `executors/src/shared/kotlin/com/point/vision/PhotoVision.kt` — «Улучшить фото»
(#433), увеличение снимков мельче 1280 точек по длинной стороне. Исполняется OpenCV DNN на самом
устройстве — телефоне или компьютере: снимок никуда не уходит.
