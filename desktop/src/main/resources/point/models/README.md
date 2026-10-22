# Модели компьютера

`u2netp.onnx` — малая U²-Net (Qin et al., «U²-Net: Going Deeper with Nested U-Structure for
Salient Object Detection», 2020) в виде ONNX, как её раздаёт rembg:
https://github.com/danielgatis/rembg/releases/download/v0.0.0/u2netp.onnx

Лицензия модели и исходного кода U²-Net — Apache License 2.0
(https://github.com/xuebinqin/U-2-Net/blob/master/LICENSE).

Чем Point её зовёт: `desktop/src/main/kotlin/com/point/desktop/BackgroundOnPc.kt` — «Убрать фон»,
«Размыть фон» и «Заменить фон» на компьютере (#1379). Модель исполняется на самом компьютере
через OpenCV DNN: снимок никуда не уходит.
